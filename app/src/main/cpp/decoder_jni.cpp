#include <jni.h>
#include "LoudnessMeter.h"
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/codec_desc.h>
#include <libavformat/avformat.h>
#include <libavutil/avstring.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libavutil/intreadwrite.h>
}

namespace {
using Clock = std::chrono::steady_clock;
struct Failure { const char* message; };
void require(bool valid, const char* message) { if (!valid) throw Failure{message}; }
constexpr const char* codecs = "pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,flac,alac,mp3float,aac,vorbis,opus,wavpack,ape,tta,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar";
// WavPack block header flag 0x8: hybrid (lossy without a .wvc correction file, which FFmpeg never reads).
bool wavpackHybrid(const AVPacket* packet) {
    return packet->size >= 32 && memcmp(packet->data, "wvpk", 4) == 0 && (AV_RL32(packet->data + 24) & 0x8) != 0;
}

struct Decoder {
    int fd = -1;
    int64_t size = 0, position = 0, bytesLeft = 0;
    std::shared_ptr<std::atomic<bool>> cancelled = std::make_shared<std::atomic<bool>>(false);
    JNIEnv* scanEnv = nullptr;
    jobject scanSignal = nullptr;
    jmethodID scanIsCancelled = nullptr;
    Clock::time_point deadline;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int stream = -1, rate = 0, bits = 0, channels = 0, frameOffset = 0, maxFrameSamples = 65536;
    AVSampleFormat representation = AV_SAMPLE_FMT_NONE;
    bool lossless = false, quantized = false, gaplessEligible = false;
    bool inputEnd = false, drainSent = false, ended = false, failed = false;
    int64_t durationUs = -1, expectedFrames = -1, totalFrames = 0;
    int64_t indexedOrigin = AV_NOPTS_VALUE;
    int64_t quantizedSamples = 0, clippedSamples = 0;

    ~Decoder() {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (fd >= 0) ::close(fd);
    }
    void beginOperation() { bytesLeft = 8 * 1024 * 1024; deadline = Clock::now() + std::chrono::seconds(5); }
    bool cancellationRequested() const {
        return cancelled->load() || (scanSignal && scanEnv->CallBooleanMethod(scanSignal, scanIsCancelled));
    }
    bool interrupted() const { return cancellationRequested() || Clock::now() > deadline; }
    void check() const {
        require(!cancellationRequested(), "DECODER_CANCELLED");
        require(Clock::now() <= deadline && bytesLeft >= 0, "DECODER_LIMIT_EXCEEDED");
    }
    static int interrupt(void* opaque) { return static_cast<Decoder*>(opaque)->interrupted(); }
    static int read(void* opaque, uint8_t* buffer, int count) {
        auto& self = *static_cast<Decoder*>(opaque);
        if (self.interrupted()) return AVERROR_EXIT;
        if (count <= 0 || count > self.bytesLeft) { self.bytesLeft = -1; return AVERROR(EFBIG); }
        ssize_t result;
        do { result = pread(self.fd, buffer, count, self.position); } while (result < 0 && errno == EINTR && !self.interrupted());
        if (result < 0) return AVERROR(errno);
        if (result == 0) return AVERROR_EOF;
        self.position += result;
        self.bytesLeft -= result;
        return static_cast<int>(result);
    }
    static int64_t seek(void* opaque, int64_t offset, int whence) {
        auto& self = *static_cast<Decoder*>(opaque);
        if (self.interrupted()) return AVERROR_EXIT;
        if (whence == AVSEEK_SIZE) return self.size;
        whence &= ~AVSEEK_FORCE;
        int64_t base;
        if (whence == SEEK_SET) base = 0;
        else if (whence == SEEK_CUR) base = self.position;
        else if (whence == SEEK_END) base = self.size;
        else return AVERROR(EINVAL);
        if ((offset < 0 && offset < -base) || (offset >= 0 && offset > self.size - base)) return AVERROR(EINVAL);
        self.position = base + offset;
        return self.position;
    }
    static int rejectSecondary(AVFormatContext*, AVIOContext**, const char*, int, AVDictionary**) { return AVERROR(EPERM); }

    void validateChunkExtents() {
        uint8_t header[12];
        if (pread(fd, header, sizeof(header), 0) != sizeof(header)) return;
        require(memcmp(header, "RF64", 4) != 0 && memcmp(header, "BW64", 4) != 0, "DECODER_RF64_UNSUPPORTED");
        const bool riff = (memcmp(header, "RIFF", 4) == 0 || memcmp(header, "RIFX", 4) == 0) && memcmp(header + 8, "WAVE", 4) == 0;
        const bool form = memcmp(header, "FORM", 4) == 0 &&
            (memcmp(header + 8, "AIFF", 4) == 0 || memcmp(header + 8, "AIFC", 4) == 0);
        if (!riff && !form) return;
        const bool little = memcmp(header, "RIFF", 4) == 0;
        auto length = [little](const uint8_t* data) -> int64_t {
            uint32_t value = 0;
            for (int i = 0; i < 4; ++i) value |= uint32_t(data[i]) << (8 * (little ? i : 3 - i));
            return value;
        };
        const int64_t end = length(header + 4) + 8;
        require(end >= 12 && end <= size, "DECODER_TRUNCATED_CONTAINER");
        int64_t cursor = 12;
        for (int chunks = 0; cursor < end; ++chunks) {
            check();
            require(chunks < 4096 && end - cursor >= 8 && pread(fd, header, 8, cursor) == 8, "DECODER_INVALID_CHUNKS");
            const int64_t bytes = length(header + 4);
            const int64_t padded = bytes + (bytes & 1);
            require(padded <= end - cursor - 8, "DECODER_TRUNCATED_CONTAINER");
            cursor += 8 + padded;
        }
    }

    bool readHeaderMetadata() {
        const auto* audio = format->streams[stream];
        const auto* parameters = audio->codecpar;
        const auto id = parameters->codec_id;
        const auto* implementation = avcodec_find_decoder(id);
        if (!implementation || !av_match_list(implementation->name, codecs, ',')) return false;
        const auto* description = avcodec_descriptor_get(id);
        const bool pcm = description && !strncmp(description->name, "pcm_", 4);
        // Other decoders can change rate, layout or precision after opening, including hybrid WavPack and DSD.
        if (!pcm && id != AV_CODEC_ID_FLAC && id != AV_CODEC_ID_ALAC) return false;
        const int headerRate = parameters->sample_rate;
        const auto& layout = parameters->ch_layout;
        if (headerRate < 8000 || headerRate > 384000 || layout.nb_channels < 1 || layout.nb_channels > 2 ||
            layout.order == AV_CHANNEL_ORDER_CUSTOM || (layout.order == AV_CHANNEL_ORDER_NATIVE &&
            layout.u.mask != (layout.nb_channels == 1 ? AV_CH_LAYOUT_MONO : AV_CH_LAYOUT_STEREO)) ||
            audio->duration <= 0 || audio->time_base.num <= 0 || audio->time_base.den <= 0) return false;
        const bool headerLossless = description && (description->props & AV_CODEC_PROP_LOSSLESS);
        const bool floating = id == AV_CODEC_ID_PCM_F32LE || id == AV_CODEC_ID_PCM_F32BE ||
            id == AV_CODEC_ID_PCM_F64LE || id == AV_CODEC_ID_PCM_F64BE;
        const bool headerQuantized = !headerLossless || floating;
        int headerBits = parameters->bits_per_raw_sample;
        if (headerBits == 0) headerBits = av_get_bits_per_sample(id);
        if (!headerQuantized && headerBits != 16 && headerBits != 24 && headerBits != 32) return false;
        rate = headerRate;
        channels = layout.nb_channels;
        bits = headerQuantized ? 0 : headerBits;
        lossless = headerLossless;
        quantized = headerQuantized;
        durationUs = av_rescale_q(audio->duration, audio->time_base, AV_TIME_BASE_Q);
        return true;
    }

    void open(int borrowedFd, bool metadataOnly = false) {
        fd = fcntl(borrowedFd, F_DUPFD_CLOEXEC, 0);
        require(fd >= 0, "DECODER_DESCRIPTOR_ERROR");
        struct stat status{};
        require(fstat(fd, &status) == 0 && S_ISREG(status.st_mode) && status.st_size > 0, "DECODER_LOCAL_REGULAR_FILE_REQUIRED");
        size = status.st_size;
        beginOperation();
        validateChunkExtents();
        auto* buffer = static_cast<uint8_t*>(av_malloc(32768));
        require(buffer != nullptr, "DECODER_ALLOCATION_FAILED");
        io = avio_alloc_context(buffer, 32768, 0, this, read, nullptr, seek);
        if (!io) { av_free(buffer); throw Failure{"DECODER_ALLOCATION_FAILED"}; }
        format = avformat_alloc_context();
        require(format != nullptr, "DECODER_ALLOCATION_FAILED");
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->interrupt_callback = {interrupt, this};
        format->io_open = rejectSecondary;
        format->probesize = 1024 * 1024;
        format->max_analyze_duration = AV_TIME_BASE;
        format->max_streams = 8;
        format->max_index_size = 1024 * 1024;
        format->error_recognition = AV_EF_CRCCHECK | AV_EF_EXPLODE;
        // DSF and DSDIFF ("iff") decode to float PCM at 1/8 of the DSD rate; that is always a conversion.
        format->format_whitelist = av_strdup("wav,aiff,flac,mov,mp3,aac,ogg,wv,ape,tta,dsf,iff");
        format->codec_whitelist = av_strdup(codecs);
        require(format->format_whitelist && format->codec_whitelist, "DECODER_ALLOCATION_FAILED");
        int result = avformat_open_input(&format, nullptr, nullptr, nullptr);
        check();
        require(result >= 0, "DECODER_CORRUPT_OR_UNSUPPORTED");
        if (metadataOnly) {
            stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
            if (stream >= 0 && readHeaderMetadata()) return;
        }
        result = avformat_find_stream_info(format, nullptr);
        check();
        require(result >= 0, "DECODER_CORRUPT_OR_UNSUPPORTED");
        stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        require(stream >= 0, "DECODER_NO_SUPPORTED_AUDIO");
        if (metadataOnly && readHeaderMetadata()) return;
        const auto* parameters = format->streams[stream]->codecpar;
        const auto* implementation = avcodec_find_decoder(parameters->codec_id);
        require(implementation != nullptr, "DECODER_NO_SUPPORTED_AUDIO");
        codec = avcodec_alloc_context3(implementation);
        require(codec != nullptr, "DECODER_ALLOCATION_FAILED");
        require(avcodec_parameters_to_context(codec, parameters) >= 0, "DECODER_CORRUPT_OR_UNSUPPORTED");
        codec->thread_count = 1;
        codec->pkt_timebase = format->streams[stream]->time_base;
        codec->max_samples = 65536 * 2;
        codec->err_recognition = AV_EF_CRCCHECK | AV_EF_EXPLODE;
        require(avcodec_open2(codec, implementation, nullptr) >= 0, "DECODER_CORRUPT_OR_UNSUPPORTED");
        rate = codec->sample_rate;
        channels = codec->ch_layout.nb_channels;
        representation = codec->sample_fmt;
        const auto* description = avcodec_descriptor_get(codec->codec_id);
        lossless = description && (description->props & AV_CODEC_PROP_LOSSLESS);
        quantized = !lossless || av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_FLT ||
            av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_DBL;
        bits = codec->bits_per_raw_sample;
        if (bits == 0) bits = av_get_bits_per_sample(codec->codec_id);
        if (quantized) bits = 0;
        require(rate >= 8000 && rate <= 384000 && channels >= 1 && channels <= 2 &&
            (quantized || bits == 16 || bits == 24 || bits == 32), "DECODER_UNSUPPORTED_FORMAT");
        // TTA emits whole rate-dependent frames; FFmpeg's cap counts samples across channels.
        if (codec->codec_id == AV_CODEC_ID_TTA) {
            maxFrameSamples = static_cast<int>(256LL * rate / 245);
            codec->max_samples = int64_t(maxFrameSamples) * channels;
        }
        require(codec->ch_layout.order != AV_CHANNEL_ORDER_CUSTOM &&
            (codec->ch_layout.order != AV_CHANNEL_ORDER_NATIVE || codec->ch_layout.u.mask ==
            (channels == 1 ? AV_CH_LAYOUT_MONO : AV_CH_LAYOUT_STEREO)), "DECODER_UNSUPPORTED_LAYOUT");
        const auto duration = format->streams[stream]->duration;
        const char* demuxer = format->iformat->name;
        const bool timed = duration != AV_NOPTS_VALUE && duration > 0;
        // FFmpeg applies packet skip/discard metadata; raw ADTS and untagged MP3 lack exact boundaries.
        gaplessEligible = lossless || (timed && (
            (codec->codec_id == AV_CODEC_ID_MP3 && !strcmp(demuxer, "mp3") && format->streams[stream]->start_time > 0) ||
            ((codec->codec_id == AV_CODEC_ID_OPUS || codec->codec_id == AV_CODEC_ID_VORBIS) && !strcmp(demuxer, "ogg"))));
        if (duration != AV_NOPTS_VALUE && duration >= 0) {
            durationUs = av_rescale_q(duration, format->streams[stream]->time_base, AV_TIME_BASE_Q);
            if (lossless && format->duration_estimation_method != AVFMT_DURATION_FROM_BITRATE) {
                expectedFrames = av_rescale_q(duration, format->streams[stream]->time_base, AVRational{1, rate});
                require(expectedFrames >= 0 && expectedFrames < INT64_MAX, "DECODER_INVALID_DURATION");
            }
        }
        if (metadataOnly && codec->codec_id != AV_CODEC_ID_WAVPACK) return;
        packet = av_packet_alloc();
        if (!metadataOnly) frame = av_frame_alloc();
        require(packet && (metadataOnly || frame), "DECODER_ALLOCATION_FAILED");
        if (timed && codec->codec_id == AV_CODEC_ID_AAC && strstr(demuxer, "mov") != nullptr) {
            // Keep the first audio packet queued: only explicit priming plus a timed end is eligible.
            for (int step = 0; step < 4096; ++step) {
                const int result = av_read_frame(format, packet);
                check();
                if (result == AVERROR_EOF) break;
                require(result >= 0 && !(packet->flags & AV_PKT_FLAG_CORRUPT), "DECODER_CORRUPT_DATA");
                if (packet->stream_index != stream) { av_packet_unref(packet); continue; }
                size_t length = 0;
                const auto* skip = av_packet_get_side_data(packet, AV_PKT_DATA_SKIP_SAMPLES, &length);
                gaplessEligible = skip && length >= 10 && AV_RL32(skip) > 0;
                break;
            }
        }
        if (codec->codec_id == AV_CODEC_ID_WAVPACK) {
            // Keep the first block queued; a hybrid stream decodes lossy and is never original integer data.
            for (int step = 0; step < 4096; ++step) {
                const int result = av_read_frame(format, packet);
                check();
                require(result >= 0 && !(packet->flags & AV_PKT_FLAG_CORRUPT), "DECODER_CORRUPT_DATA");
                if (packet->stream_index != stream) { av_packet_unref(packet); continue; }
                if (wavpackHybrid(packet)) { lossless = false; quantized = true; bits = 0; }
                break;
            }
        }
    }

    bool nextFrame() {
        for (int steps = 0; steps < 4096; ++steps) {
            check();
            int result = avcodec_receive_frame(codec, frame);
            if (result >= 0) {
                require(frame->sample_rate == rate && frame->ch_layout.nb_channels == channels,
                    "DECODER_FORMAT_CHANGE_UNSUPPORTED");
                require(frame->ch_layout.order != AV_CHANNEL_ORDER_CUSTOM &&
                    (frame->ch_layout.order != AV_CHANNEL_ORDER_NATIVE || frame->ch_layout.u.mask ==
                    (channels == 1 ? AV_CH_LAYOUT_MONO : AV_CH_LAYOUT_STEREO)), "DECODER_UNSUPPORTED_LAYOUT");
                require(frame->nb_samples > 0 && frame->nb_samples <= maxFrameSamples &&
                    frame->format == representation &&
                    (av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_S16 ||
                     av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_S32 ||
                     av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_FLT ||
                     av_get_packed_sample_fmt(representation) == AV_SAMPLE_FMT_DBL), "DECODER_UNSUPPORTED_FORMAT");
                require(quantized || bits == 16 || frame->format == AV_SAMPLE_FMT_S32 || frame->format == AV_SAMPLE_FMT_S32P,
                    "DECODER_UNSUPPORTED_FORMAT");
                frameOffset = 0;
                if (indexedOrigin != AV_NOPTS_VALUE) {
                    require(frame->pts != AV_NOPTS_VALUE && frame->pts >= indexedOrigin &&
                        (indexedOrigin >= 0 || frame->pts <= INT64_MAX + indexedOrigin), "DECODER_SEEK_TIMESTAMP_UNAVAILABLE");
                    require(av_rescale_q(frame->pts - indexedOrigin, format->streams[stream]->time_base,
                        AVRational{1, rate}) == totalFrames, "DECODER_DISCONTINUOUS_TIMESTAMP");
                }
                return true;
            }
            if (result == AVERROR_EOF) {
                require(totalFrames > 0, "DECODER_EMPTY_AUDIO");
                require(expectedFrames < 0 || totalFrames == expectedFrames, "DECODER_TRUNCATED_OR_INVALID_DURATION");
                ended = true;
                return false;
            }
            require(result == AVERROR(EAGAIN), "DECODER_CORRUPT_DATA");
            if (packet->size > 0) {
                result = avcodec_send_packet(codec, packet);
                // receive EAGAIN followed by send EAGAIN violates the codec contract.
                require(result >= 0, "DECODER_CORRUPT_DATA");
                av_packet_unref(packet);
                continue;
            }
            if (inputEnd) {
                require(!drainSent, "DECODER_CORRUPT_DATA");
                require(avcodec_send_packet(codec, nullptr) >= 0, "DECODER_CORRUPT_DATA");
                drainSent = true;
                continue;
            }
            result = av_read_frame(format, packet);
            check();
            if (result == AVERROR_EOF) { inputEnd = true; continue; }
            require(result >= 0 && !(packet->flags & AV_PKT_FLAG_CORRUPT), "DECODER_CORRUPT_DATA");
            if (packet->stream_index != stream) av_packet_unref(packet);
            else require(quantized || codec->codec_id != AV_CODEC_ID_WAVPACK || !wavpackHybrid(packet),
                "DECODER_FORMAT_CHANGE_UNSUPPORTED");
        }
        throw Failure{"DECODER_LIMIT_EXCEEDED"};
    }

    int readPcm(uint8_t* output, int maxFrames) {
        require(!failed, "DECODER_FAILED_REOPEN_REQUIRED");
        beginOperation();
        try {
            check();
            if (ended) return 0;
            if (frameOffset >= frame->nb_samples) {
                av_frame_unref(frame);
                if (!nextFrame()) return 0;
            }
            const int count = std::min(maxFrames, frame->nb_samples - frameOffset);
            const bool planar = av_sample_fmt_is_planar(static_cast<AVSampleFormat>(frame->format));
            const int width = av_get_bytes_per_sample(static_cast<AVSampleFormat>(frame->format));
            const auto packed = av_get_packed_sample_fmt(representation);
            int64_t clipped = 0;
            for (int n = 0; n < count; ++n) for (int ch = 0; ch < channels; ++ch) {
                const auto* input = frame->extended_data[planar ? ch : 0] +
                    ((frameOffset + n) * (planar ? 1 : channels) + (planar ? 0 : ch)) * width;
                uint32_t value;
                if (packed == AV_SAMPLE_FMT_FLT || packed == AV_SAMPLE_FMT_DBL) {
                    double number;
                    if (packed == AV_SAMPLE_FMT_FLT) { float small; memcpy(&small, input, 4); number = small; }
                    else memcpy(&number, input, 8);
                    require(std::isfinite(number), "DECODER_NONFINITE_PCM");
                    int32_t integer;
                    if (number < -1.0) { integer = INT32_MIN; ++clipped; }
                    else if (number >= 1.0) { integer = INT32_MAX; ++clipped; }
                    else {
                        const double rounded = std::round(number * 2147483648.0);
                        if (rounded > INT32_MAX) { integer = INT32_MAX; ++clipped; }
                        else integer = static_cast<int32_t>(rounded);
                    }
                    memcpy(&value, &integer, 4);
                } else if (width == 2) { uint16_t small; memcpy(&small, input, 2); value = uint32_t(small) << 16; }
                else memcpy(&value, input, 4);
                const auto unused = 32 - bits;
                require(quantized || unused == 0 || (value & ((uint32_t{1} << unused) - 1)) == 0, "DECODER_PRECISION_MISMATCH");
                memcpy(output + (n * channels + ch) * 4, &value, 4);
            }
            check();
            frameOffset += count;
            require(totalFrames <= INT64_MAX - count, "DECODER_FRAME_COUNT_OVERFLOW");
            totalFrames += count;
            require(quantizedSamples <= INT64_MAX - int64_t(count) * channels && clippedSamples <= INT64_MAX - clipped,
                "DECODER_SAMPLE_COUNT_OVERFLOW");
            if (quantized) quantizedSamples += int64_t(count) * channels;
            clippedSamples += clipped;
            return count;
        } catch (...) { failed = true; throw; }
    }

    void skipTo(int64_t target) {
        while (totalFrames < target) {
            check();
            if (frameOffset >= frame->nb_samples) {
                av_frame_unref(frame);
                require(nextFrame(), "DECODER_SEEK_PAST_END");
            }
            const int count = static_cast<int>(std::min(target - totalFrames, int64_t(frame->nb_samples - frameOffset)));
            frameOffset += count;
            totalFrames += count;
        }
        check();
    }

    void seekToFrame(int64_t target) {
        beginOperation();
        if (target > int64_t(rate) * 3) {
            // These demuxers build a generic index by scanning packets, then reread preroll.
            if (strcmp(format->iformat->name, "mp3") == 0 || strcmp(format->iformat->name, "aac") == 0 ||
                strcmp(format->iformat->name, "wv") == 0 || strcmp(format->iformat->name, "dsf") == 0 ||
                strcmp(format->iformat->name, "iff") == 0)
                bytesLeft += std::min(size, INT64_MAX - bytesLeft);
            require(nextFrame(), "DECODER_SEEK_PAST_END");
            const int64_t origin = frame->pts;
            require(origin != AV_NOPTS_VALUE, "DECODER_SEEK_TIMESTAMP_UNAVAILABLE");
            const auto timeBase = format->streams[stream]->time_base;
            require(timeBase.num > 0 && timeBase.den > 0, "DECODER_SEEK_TIMESTAMP_UNAVAILABLE");
            const int64_t offset = av_rescale_q(target - int64_t(rate) * 3, AVRational{1, rate}, timeBase);
            require(offset >= 0 && origin <= INT64_MAX - offset, "DECODER_INVALID_SEEK");
            const int result = av_seek_frame(format, stream, origin + offset, AVSEEK_FLAG_BACKWARD);
            check();
            require(result >= 0, "DECODER_SEEK_UNSUPPORTED");
            avcodec_flush_buffers(codec);
            av_packet_unref(packet); av_frame_unref(frame);
            frameOffset = 0; inputEnd = drainSent = ended = false;
            require(nextFrame(), "DECODER_SEEK_PAST_END");
            require(frame->pts != AV_NOPTS_VALUE && frame->pts >= origin &&
                (origin >= 0 || frame->pts <= INT64_MAX + origin), "DECODER_SEEK_TIMESTAMP_UNAVAILABLE");
            totalFrames = av_rescale_q(frame->pts - origin, timeBase, AVRational{1, rate});
            require(totalFrames >= 0 && totalFrames <= target, "DECODER_SEEK_TIMESTAMP_UNAVAILABLE");
            indexedOrigin = origin;
        }
        skipTo(target);
    }
};

std::mutex handlesMutex;
std::unordered_map<jlong, std::shared_ptr<Decoder>> handles;
jlong nextHandle = 1;
std::shared_ptr<Decoder> lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = handles.find(handle);
    require(found != handles.end(), "DECODER_CLOSED");
    return found->second;
}
void failJava(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        auto type = env->FindClass("java/io/IOException");
        if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
    }
}
template<typename F> auto guarded(JNIEnv* env, F action) -> decltype(action()) {
    try { return action(); }
    catch (const Failure& failure) { failJava(env, failure.message); }
    catch (const std::bad_alloc&) { failJava(env, "DECODER_ALLOCATION_FAILED"); }
    catch (...) { failJava(env, "DECODER_INTERNAL_ERROR"); }
    return {};
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    av_log_set_level(AV_LOG_QUIET);
    av_max_alloc(16 * 1024 * 1024);
    return JNI_VERSION_1_6;
}
static jlongArray openDecoder(JNIEnv* env, jint fd, jobject signal) {
    return guarded(env, [&]() -> jlongArray {
        auto decoder = std::make_shared<Decoder>();
        if (signal) {
            auto type = env->GetObjectClass(signal);
            if (!type) return nullptr;
            decoder->scanIsCancelled = env->GetMethodID(type, "isCanceled", "()Z");
            env->DeleteLocalRef(type);
            if (!decoder->scanIsCancelled) return nullptr;
            decoder->scanEnv = env;
            decoder->scanSignal = signal;
        }
        decoder->open(fd, signal != nullptr);
        if (signal) decoder->check();
        decoder->scanSignal = nullptr;
        decoder->scanEnv = nullptr;
        auto array = env->NewLongArray(8);
        if (!array) return nullptr;
        std::lock_guard<std::mutex> lock(handlesMutex);
        require(nextHandle > 0 && nextHandle < INT64_MAX, "DECODER_HANDLE_LIMIT");
        const jlong id = nextHandle++;
        const jlong info[] = {id, decoder->rate, decoder->bits, decoder->channels, decoder->durationUs, decoder->lossless, decoder->quantized, decoder->gaplessEligible};
        env->SetLongArrayRegion(array, 0, 8, info);
        if (env->ExceptionCheck()) return nullptr;
        handles.emplace(id, std::move(decoder));
        return array;
    });
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeOpen(JNIEnv* env, jclass, jint fd) {
    return openDecoder(env, fd, nullptr);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeInspect(JNIEnv* env, jclass, jint fd, jobject signal) {
    return openDecoder(env, fd, signal);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeCodec(JNIEnv* env, jclass, jlong id) {
    return guarded(env, [&] {
        const auto decoder = lookup(id);
        return env->NewStringUTF(avcodec_get_name(decoder->format->streams[decoder->stream]->codecpar->codec_id));
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeTags(JNIEnv* env, jclass, jlong id) {
    return guarded(env, [&]() -> jobjectArray {
        auto decoder = lookup(id);
        auto type = env->FindClass("[B");
        if (!type) return nullptr;
        const char* keys[] = {"title", "artist", "album", "album_artist", "track", "tracktotal", "totaltracks",
            "disc", "disctotal", "totaldiscs", "date", "year", "TYER", "genre", "composer", "compilation",
            "title-sort", "artist-sort", "album-sort", "sort_album_artist"};
        const char* sortAliases[][3] = {{"sort_name", "titlesort", "sort_title"}, {"sort_artist", "artistsort", nullptr},
            {"sort_album", "albumsort", nullptr}, {"album_artist_sort", "albumartistsort", "albumartist-sort"}};
        constexpr auto count = sizeof(keys) / sizeof(keys[0]);
        auto result = env->NewObjectArray(count, type, nullptr);
        env->DeleteLocalRef(type);
        if (!result) return nullptr;
        for (size_t i = 0; i < count; ++i) {
            AVDictionaryEntry* tag = nullptr;
            AVDictionary* dictionaries[] = {decoder->format->streams[decoder->stream]->metadata, decoder->format->metadata};
            for (auto* dictionary : dictionaries) {
                tag = av_dict_get(dictionary, keys[i], nullptr, 0);
                if (i >= 16 && !tag) for (const auto* alias : sortAliases[i - 16]) {
                    if (alias) tag = av_dict_get(dictionary, alias, nullptr, 0);
                    if (tag) break;
                }
                if (tag) break;
            }
            const auto length = tag ? static_cast<jsize>(strnlen(tag->value, 4096)) : 0;
            auto bytes = env->NewByteArray(length);
            if (!bytes) return nullptr;
            if (length) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(tag->value));
            if (!env->ExceptionCheck()) env->SetObjectArrayElement(result, i, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeRead(JNIEnv* env, jclass, jlong id, jobject buffer, jint frames) {
    return guarded(env, [&] {
        auto decoder = lookup(id);
        auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
        const auto capacity = env->GetDirectBufferCapacity(buffer);
        require(frames >= 1 && frames <= 4096 && base && reinterpret_cast<uintptr_t>(base) % 4 == 0 &&
            capacity >= int64_t(frames) * decoder->channels * 4, "DECODER_INVALID_BUFFER");
        return decoder->readPcm(base, frames);
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeGainTags(JNIEnv* env,jclass,jlong id) {
    return guarded(env,[&]() -> jobjectArray {
        auto decoder=lookup(id);
        const char* keys[]={"replaygain_track_gain","replaygain_album_gain","replaygain_track_peak","replaygain_album_peak","r128_track_gain","r128_album_gain","iTunNORM"};
        auto type=env->FindClass("[B");if(!type)return nullptr;
        auto result=env->NewObjectArray(7,type,nullptr);env->DeleteLocalRef(type);if(!result)return nullptr;
        for(int i=0;i<7;++i) {
            AVDictionaryEntry* tag=av_dict_get(decoder->format->streams[decoder->stream]->metadata,keys[i],nullptr,0);
            if(!tag)tag=av_dict_get(decoder->format->metadata,keys[i],nullptr,0);
            const auto length=tag ? static_cast<jsize>(strnlen(tag->value,129)) : 0;
            auto bytes=env->NewByteArray(length);if(!bytes)return nullptr;
            if(length)env->SetByteArrayRegion(bytes,0,length,reinterpret_cast<const jbyte*>(tag->value));
            if(!env->ExceptionCheck())env->SetObjectArrayElement(result,i,bytes);
            env->DeleteLocalRef(bytes);if(env->ExceptionCheck())return nullptr;
        }
        return result;
    });
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeArtwork(JNIEnv* env, jclass, jlong id) {
    return guarded(env, [&]() -> jbyteArray {
        auto decoder = lookup(id);
        require(!decoder->cancelled->load(), "DECODER_CANCELLED");
        constexpr int maxArtworkBytes = 8 * 1024 * 1024; // Match the managed artwork decode limit.
        const AVPacket* selected = nullptr;
        for (unsigned i = 0; i < decoder->format->nb_streams; ++i) {
            const auto* stream = decoder->format->streams[i];
            const auto& picture = stream->attached_pic;
            if (!(stream->disposition & AV_DISPOSITION_ATTACHED_PIC) || !picture.data ||
                picture.size <= 0 || picture.size > maxArtworkBytes) continue;
            if (!selected) selected = &picture;
            const auto* type = av_dict_get(stream->metadata, "comment", nullptr, 0);
            if (type && !strcmp(type->value, "Cover (front)")) { selected = &picture; break; }
        }
        if (!selected) return nullptr;
        auto result = env->NewByteArray(selected->size);
        if (result) env->SetByteArrayRegion(result, 0, selected->size, reinterpret_cast<const jbyte*>(selected->data));
        return result;
    });
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeMeasureLoudness(JNIEnv* env,jclass,jlong id) {
    return guarded(env,[&]() -> jdoubleArray {
        auto decoder=lookup(id);
        require(decoder->totalFrames==0 && !decoder->failed,"LOUDNESS_FRESH_DECODER_REQUIRED");
        evanna::LoudnessMeter meter(decoder->rate,decoder->channels);
        std::array<int32_t,4096*2> block;
        for(;;) {
            require(!decoder->cancelled->load(),"DECODER_CANCELLED");
            const int frames=decoder->readPcm(reinterpret_cast<uint8_t*>(block.data()),4096);
            if(frames==0)break;
            meter.add(block.data(),static_cast<unsigned>(frames));
        }
        const auto measured=meter.result();
        const jdouble values[]={measured.lufs,measured.peak,static_cast<jdouble>(measured.frames)};
        auto result=env->NewDoubleArray(3);
        if(result)env->SetDoubleArrayRegion(result,0,3,values);
        return result;
    });
}
// Embedded lyric texts as stored: MP4 "©lyr" and Vorbis/APE LYRICS appear as "lyrics", ID3 USLT as
// "lyrics-<description>-<language>", plus the UNSYNCEDLYRICS spellings. At most four, each up to 256 KiB.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeLyrics(JNIEnv* env, jclass, jlong id) {
    return guarded(env, [&]() -> jobjectArray {
        auto decoder = lookup(id);
        require(!decoder->cancelled->load(), "DECODER_CANCELLED");
        constexpr size_t limit = 256 * 1024;
        const AVDictionaryEntry* found[4] = {};
        jsize count = 0;
        for (const AVDictionary* metadata : {decoder->format->streams[decoder->stream]->metadata, decoder->format->metadata}) {
            const AVDictionaryEntry* tag = nullptr;
            while (count < 4 && (tag = av_dict_get(metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
                const bool lyrics = !av_strcasecmp(tag->key, "lyrics") || !av_strncasecmp(tag->key, "lyrics-", 7) ||
                    !av_strcasecmp(tag->key, "unsyncedlyrics") || !av_strcasecmp(tag->key, "unsynced lyrics");
                const auto length = strnlen(tag->value, limit + 1);
                if (lyrics && length > 0 && length <= limit) found[count++] = tag;
            }
        }
        auto type = env->FindClass("[B");
        if (!type) return nullptr;
        auto result = env->NewObjectArray(count, type, nullptr);
        env->DeleteLocalRef(type);
        if (!result) return nullptr;
        for (jsize i = 0; i < count; ++i) {
            const auto length = static_cast<jsize>(strnlen(found[i]->value, limit));
            auto bytes = env->NewByteArray(length);
            if (!bytes) return nullptr;
            env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(found[i]->value));
            if (!env->ExceptionCheck()) env->SetObjectArrayElement(result, i, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeCancel(JNIEnv* env, jclass, jlong id) {
    guarded(env, [&] { lookup(id)->cancelled->store(true); return 0; });
}
extern "C" JNIEXPORT void JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeSeek(JNIEnv* env, jclass, jlong id, jlong positionUs) {
    guarded(env, [&] {
        auto original = lookup(id);
        require(positionUs >= 0 && !original->failed, "DECODER_INVALID_SEEK");
        require(!original->cancelled->load(), "DECODER_CANCELLED");
        const int64_t target = av_rescale_rnd(positionUs, original->rate, AV_TIME_BASE, AV_ROUND_UP);
        require(target >= 0 && target < INT64_MAX, "DECODER_INVALID_SEEK");
        auto replacement = std::make_shared<Decoder>();
        replacement->cancelled = original->cancelled;
        replacement->open(original->fd);
        require(replacement->rate == original->rate && replacement->bits == original->bits &&
            replacement->channels == original->channels && replacement->representation == original->representation &&
            replacement->codec->codec_id == original->codec->codec_id, "DECODER_FORMAT_CHANGE_UNSUPPORTED");
        replacement->seekToFrame(target);
        std::lock_guard<std::mutex> lock(handlesMutex);
        auto found = handles.find(id);
        require(found != handles.end() && found->second == original, "DECODER_CLOSED");
        found->second = std::move(replacement);
        return 0;
    });
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeStats(JNIEnv* env, jclass, jlong id) {
    return guarded(env, [&]() -> jlongArray {
        auto decoder = lookup(id);
        auto result = env->NewLongArray(3);
        if (result) {
            const jlong values[] = {decoder->totalFrames, decoder->quantizedSamples, decoder->clippedSamples};
            env->SetLongArrayRegion(result, 0, 3, values);
        }
        return result;
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_evanna_player_decoder_NativeDecoder_nativeClose(JNIEnv* env, jclass, jlong id) {
    guarded(env, [&] {
        std::lock_guard<std::mutex> lock(handlesMutex);
        handles.erase(id);
        return 0;
    });
}
