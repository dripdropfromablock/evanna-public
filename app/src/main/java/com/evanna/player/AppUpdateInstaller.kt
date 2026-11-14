package com.evanna.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an update stands. The Settings row and the update alerts all read this one value. */
internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class CheckFailed(val message: String) : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    /** [fraction] is null until the size is known. */
    data class Downloading(val update: AppUpdate, val fraction: Float?) : UpdateState
    /** The checked APK is on disk; Android first needs the user to let Evanna install apps. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState
    /** Handed to Android, which asks the user and then replaces the app. */
    data class Installing(val update: AppUpdate) : UpdateState
    data class InstallFailed(val update: AppUpdate, val message: String) : UpdateState
}

/** A problem worth telling the user in its own words. */
private class UpdateProblem(message: String) : Exception(message)

private const val UPDATE_LIMIT_BYTES = 200L * 1024 * 1024

/** A download as one line: "Downloading 0.2.0… 45%". */
internal fun downloadLine(version: String, fraction: Float?): String =
    "Downloading $version…" + (fraction?.let { " ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "")

/**
 * Checks for, downloads and installs Evanna updates from its GitHub releases. The APK must match the SHA-256 in the
 * release's SHA256SUMS.txt and be Evanna, at the release's version, signed with the key of the copy installed now;
 * Android's installer then asks the user and checks the signature again.
 */
internal object AppUpdater {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var ready: File? = null

    private val working get() = state.let {
        it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing || it is UpdateState.NeedsPermission
    }

    /** The daily check's answer, shown unless the user is in the middle of something. */
    fun offer(update: AppUpdate?) {
        if (working) return
        state = when {
            update != null -> UpdateState.Available(update)
            state is UpdateState.Available -> UpdateState.Idle
            else -> state
        }
    }

    fun check(context: Context, installed: Long) {
        if (working) return
        val preferences = context.getSharedPreferences(UI_SETTINGS, Context.MODE_PRIVATE)
        state = UpdateState.Checking
        job = scope.launch {
            state = try {
                val found = latestUpdate(installed)
                preferences.saveUpdateCheck(found)
                if (found == null) UpdateState.UpToDate else UpdateState.Available(found)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UpdateState.CheckFailed("Couldn't reach GitHub. Check the internet and try again.")
            }
        }
    }

    fun install(context: Context, update: AppUpdate) {
        if (working) return
        val app = context.applicationContext
        state = UpdateState.Downloading(update, null)
        job = scope.launch {
            val self = coroutineContext[Job]
            try {
                // A cancelled download must not paint its progress over the offer that Cancel put back.
                val apk = downloadVerified(app, update) { fraction ->
                    if (self?.isActive != false) state = UpdateState.Downloading(update, fraction)
                }
                ready = apk
                handOver(app, update, apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: UpdateProblem) {
                state = UpdateState.InstallFailed(update, problem.message.orEmpty())
            } catch (_: Exception) {
                state = UpdateState.InstallFailed(update, "The download stopped. Check the internet and try again.")
            }
        }
    }

    fun cancel() {
        val update = when (val now = state) {
            is UpdateState.Downloading -> now.update
            is UpdateState.NeedsPermission -> now.update
            is UpdateState.InstallFailed -> now.update
            else -> return
        }
        job?.cancel()
        ready?.delete()
        ready = null
        state = UpdateState.Available(update)
    }

    /** Opens Android's "Install unknown apps" switch for Evanna. */
    fun openInstallPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }

    /** Back from Android's settings: carry on if the user allowed installs. */
    fun resume(context: Context) {
        val waiting = state as? UpdateState.NeedsPermission ?: return
        val apk = ready
        if (apk == null) { state = UpdateState.Available(waiting.update); return }
        if (!context.packageManager.canRequestPackageInstalls()) return
        scope.launch { handOver(context.applicationContext, waiting.update, apk) }
    }

    private suspend fun handOver(context: Context, update: AppUpdate, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.NeedsPermission(update)
            return
        }
        state = UpdateState.Installing(update)
        try {
            withContext(Dispatchers.IO) { commitInstall(context, apk) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state = UpdateState.InstallFailed(update, "Android couldn't start the install. Try again.")
        }
    }

    /** What Android's installer reported. On success Android replaces the app, so this rarely runs. */
    fun installFinished(status: Int, message: String?) {
        val update = (state as? UpdateState.Installing)?.update ?: return
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            // The user tapped Cancel on Android's screen: back to the offer, no error.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(update)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.InstallFailed(update, "Android can't install this over the Evanna you have." + (message?.let { " ($it)" } ?: ""))
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.InstallFailed(update, "Not enough free space to install the update.")
            else -> UpdateState.InstallFailed(update, "Android didn't install the update." + (message?.let { " ($it)" } ?: ""))
        }
        if (state !is UpdateState.InstallFailed) { ready?.delete(); ready = null }
    }
}

private suspend fun downloadVerified(context: Context, update: AppUpdate, progress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
    val sums = update.checksumsUrl ?: throw UpdateProblem("This release has no checksum file, so Evanna can't check the download.")
    val name = update.downloadUrl.substringAfterLast('/')
    val expected = checksumFor(readHttps(sums, 64 * 1024).orEmpty(), name)
        ?: throw UpdateProblem("The release's checksum file doesn't list this download.")
    val folder = File(context.cacheDir, "updates")
    folder.deleteRecursively()
    if (!folder.mkdirs()) throw UpdateProblem("Evanna couldn't make room for the download.")
    val apk = File(folder, "evanna-${update.versionCode}.apk")
    try {
        val actual = downloadApk(update.downloadUrl, apk, progress)
        if (actual != expected) throw UpdateProblem("The download didn't match its checksum. Try again.")
        checkPackage(context, apk, update)
        apk
    } catch (failure: Exception) {
        apk.delete()
        throw failure
    }
}

/** Streams the APK to [target] and returns its SHA-256 in lower-case hex. */
private suspend fun downloadApk(url: String, target: File, progress: (Float?) -> Unit): String {
    val connection = openHttps(url, 15_000) ?: throw IOException("Update asset missing")
    try {
        val total = connection.contentLengthLong.takeIf { it > 0 }
        if (total != null && total > UPDATE_LIMIT_BYTES) throw UpdateProblem("The download is too large.")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastPercent = -1
        progress(if (total == null) null else 0f)
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    done += count
                    if (done > UPDATE_LIMIT_BYTES) throw UpdateProblem("The download is too large.")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (total != null) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(done.toFloat() / total) }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    } finally {
        connection.disconnect()
    }
}

/** The APK must be Evanna, at the release's version, signed by a key the installed copy has. */
private fun checkPackage(context: Context, apk: File, update: AppUpdate) {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        @Suppress("DEPRECATION") manager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
    } ?: throw UpdateProblem("The download isn't a working app.")
    if (info.packageName != context.packageName) throw UpdateProblem("The download isn't Evanna.")
    if (info.longVersionCode != update.versionCode) throw UpdateProblem("The download's version doesn't match the release.")
    val signing = info.signingInfo
    val signers = when {
        signing == null -> null
        signing.hasMultipleSigners() -> signing.apkContentsSigners
        else -> signing.signingCertificateHistory
    }
    // Some Android versions leave an archive's signers out; Android's installer still checks them.
    if (!signers.isNullOrEmpty() && signers.none {
            manager.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }) {
        throw UpdateProblem("The download isn't signed with this Evanna's key, so Android can't install it over this copy.")
    }
}

private fun commitInstall(context: Context, apk: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(apk.length())
    }
    val id = installer.createSession(params)
    var committed = false
    try {
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val reply = PendingIntent.getBroadcast(context, id,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            session.commit(reply.intentSender)
            committed = true
        }
    } finally {
        if (!committed) runCatching { installer.abandonSession(id) }
    }
}

/**
 * Hears back from Android's installer. Not exported: only the reply Evanna hands the installer reaches it. When the
 * user has to confirm, it opens Android's own install screen.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            AppUpdater.installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            return
        }
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        try {
            context.startActivity(checkNotNull(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            AppUpdater.installFinished(PackageInstaller.STATUS_FAILURE, "the install screen didn't open")
        }
    }
}
