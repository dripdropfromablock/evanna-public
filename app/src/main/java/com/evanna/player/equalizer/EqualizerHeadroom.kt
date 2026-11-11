package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import java.util.PriorityQueue
import kotlin.math.*

internal data class EqualizerHeadroom(val preampDb:Float?,val requiredDb:Double,val processed:Boolean)

internal fun safeEqualizerPreampDb(requestedDb: Float, requiredDb: Double): Float? {
    val safe = floor(minOf(requestedDb.toDouble(), -requiredDb) * 10) / 10
    return safe.takeIf { it >= -20 && it.isFinite() }?.toFloat()
}

/**
 * Keeps the combined sine response at or below 0 dB on each channel. The peak/shelf chain gets a conservative bound;
 * pass bands add complex responses, so a dense sweep finds their peak. Arbitrary transient peaks still need clipping counters.
 */
internal fun equalizerHeadroom(config:EqualizerConfig,rate:Int):EqualizerHeadroom {
    require(rate in 8000..768000)
    val sections=sections(config,rate)
    if(sections.isEmpty())return EqualizerHeadroom(config.preampDb,0.0,abs(10.0.pow(config.preampDb/20.0)-1)>1e-9)
    return try {
        fun peakFor(active:List<Section>)=if(active.any(Section::parallel))sweptPeak(active,rate) else chainPeak(active)
        val peak=if(sections.all {it.channels==0})peakFor(sections)
            else (0..1).maxOf {channel -> peakFor(sections.filter {it.applies(channel)})}
        val required=if(peak>0.000001)peak+0.2 else 0.0
        EqualizerHeadroom(safeEqualizerPreampDb(config.preampDb,required),required,true)
    }catch(_:IllegalArgumentException){EqualizerHeadroom(null,Double.POSITIVE_INFINITY,true)}
}

/** [points] frequencies spread evenly on a log axis from 20 Hz to 24 kHz, the EQ curve's span. */
internal fun curveFrequencies(points:Int):DoubleArray {
    require(points>=2)
    val low=log10(20.0);val high=log10(24000.0)
    return DoubleArray(points) {10.0.pow(low+(high-low)*it/(points-1))}
}

/**
 * The EQ screen's curve: the bands' response in dB on one channel at each frequency, with the same math as the headroom
 * and playback at [rate]. The preamp is left out (the screen shows it on its own), and values within 0.08 dB of 0 are 0.
 */
internal fun equalizerCurveDb(config:EqualizerConfig,frequencies:DoubleArray,rate:Int=48000,channel:Int=0):DoubleArray {
    require(rate in 8000..768000 && channel in 0..1)
    val active=sections(config,rate).filter {it.applies(channel)}
    return DoubleArray(frequencies.size) {
        val db=responseDb(active,2*PI*frequencies[it].coerceIn(0.0,rate/2.0)/rate)
        if(abs(db)<0.08)0.0 else db
    }
}

/** Response in dB at [frequency] on one channel (0 left, 1 right), preamp included. */
internal fun equalizerResponseDb(config:EqualizerConfig,frequency:Double,rate:Int,channel:Int=0):Double {
    require(rate in 8000..768000 && frequency.isFinite() && frequency in 0.0..rate/2.0 && channel in 0..1)
    return config.preampDb+responseDb(sections(config,rate).filter {it.applies(channel)},2*PI*frequency/rate)
}

private fun sections(config:EqualizerConfig,rate:Int)=config.bands.filter {abs(it.gainDb)>=0.01 && it.frequencyHz<rate*0.49}.map {Section(it,rate)}

/** Bounds each section on a shared t=sin²(w/2) interval with its exact extrema, then splits the intervals with the highest bound. */
private fun chainPeak(sections:List<Section>):Double {
    if(sections.isEmpty())return 0.0
    fun response(t:Double)=sections.sumOf {it.gain(t)}
    fun upper(lo:Double,hi:Double)=sections.sumOf {it.maximum(lo,hi)}
    data class Interval(val lo:Double,val hi:Double,val upper:Double)
    val queue=PriorityQueue<Interval>(compareByDescending {it.upper})
    var best=maxOf(0.0,response(0.0),response(1.0),response(0.5))
    queue.add(Interval(0.0,1.0,upper(0.0,1.0)))
    var splits=0
    while((queue.peek()?.upper ?: best)>best+0.005 && splits++<8192) {
        val interval=queue.remove();val middle=(interval.lo+interval.hi)/2
        best=maxOf(best,response(middle))
        for((lo,hi) in listOf(interval.lo to middle,middle to interval.hi)) {
            val limit=upper(lo,hi)
            if(limit>best)queue.add(Interval(lo,hi,limit))
        }
    }
    return maxOf(best,queue.peek()?.upper ?: best)
}

// shortcut: a sampled peak, not a strict bound; the 0.2 dB margin covers the steps (0.24% apart even at 384 kHz). Use an interval bound if margins shrink.
private fun sweptPeak(sections:List<Section>,rate:Int):Double {
    val steps=4096;val span=ln(rate/2.0/10.0)
    var best=maxOf(0.0,responseDb(sections,0.0),responseDb(sections,PI))
    for(i in 0..steps)best=maxOf(best,responseDb(sections,2*PI*10.0*exp(span*i/steps)/rate))
    return best
}

/** Pass bands filter the dry input and their outputs add to it; the peak/shelf chain then multiplies that sum. */
private fun responseDb(sections:List<Section>,w:Double):Double {
    var re=1.0;var im=0.0;var chainDb=0.0
    for(s in sections) {
        if(s.parallel){val (r,i)=s.complex(w);re+=r;im+=i}else chainDb+=s.response(w)
    }
    return chainDb+20*log10(hypot(re,im))
}

/** Same RBJ coefficients as the native Equalizer; for the chain, extrema are expressed in t=sin²(w/2). */
private class Section(band:EqBand,rate:Int) {
    val parallel=band.type==EqBand.Type.LOW_PASS || band.type==EqBand.Type.HIGH_PASS || band.type==EqBand.Type.BAND_PASS
    val channels=band.channels
    private val b:DoubleArray
    private val a:DoubleArray
    private val top:DoubleArray
    private val bottom:DoubleArray
    private val roots:List<Double>
    init {
        val gain=10.0.pow(band.gainDb/40.0);val w=2*PI*band.frequencyHz/rate
        val c=cos(w);val alpha=sin(w)/(2*band.q);val shelf=2*sqrt(gain)*alpha
        val raw=when(band.type) {
            EqBand.Type.PEAK -> doubleArrayOf(1+alpha*gain,-2*c,1-alpha*gain,1+alpha/gain,-2*c,1-alpha/gain)
            EqBand.Type.LOW_SHELF -> doubleArrayOf(gain*((gain+1)-(gain-1)*c+shelf),2*gain*((gain-1)-(gain+1)*c),gain*((gain+1)-(gain-1)*c-shelf),
                (gain+1)+(gain-1)*c+shelf,-2*((gain-1)+(gain+1)*c),(gain+1)+(gain-1)*c-shelf)
            EqBand.Type.HIGH_SHELF -> doubleArrayOf(gain*((gain+1)+(gain-1)*c+shelf),-2*gain*((gain-1)+(gain+1)*c),gain*((gain+1)+(gain-1)*c-shelf),
                (gain+1)-(gain-1)*c+shelf,2*((gain-1)-(gain+1)*c),(gain+1)-(gain-1)*c-shelf)
            EqBand.Type.LOW_PASS -> doubleArrayOf((1-c)/2,1-c,(1-c)/2,1+alpha,-2*c,1-alpha)
            EqBand.Type.HIGH_PASS -> doubleArrayOf((1+c)/2,-(1+c),(1+c)/2,1+alpha,-2*c,1-alpha)
            EqBand.Type.BAND_PASS -> doubleArrayOf(alpha,0.0,-alpha,1+alpha,-2*c,1-alpha)
        }
        // A pass band adds 10^(dB/20) - 1 of its output to the dry signal, as the native engine does.
        val scale=if(parallel)10.0.pow(band.gainDb/20.0)-1 else 1.0
        b=DoubleArray(3){raw[it]/raw[3]*scale};a=doubleArrayOf(1.0,raw[4]/raw[3],raw[5]/raw[3])
        fun polynomial(v:DoubleArray)=doubleArrayOf((v[0]+v[1]+v[2]).pow(2),-4*(v[1]*(v[0]+v[2])+4*v[0]*v[2]),16*v[0]*v[2])
        top=polynomial(b);bottom=polynomial(a)
        roots=if(parallel)emptyList() else quadraticRoots(top[2]*bottom[1]-top[1]*bottom[2],2*(top[2]*bottom[0]-top[0]*bottom[2]),top[1]*bottom[0]-top[0]*bottom[1])
            .filter {it.isFinite() && it>0 && it<1}
    }
    fun applies(channel:Int)=channels==0 || channels==channel+1
    fun gain(t:Double):Double {
        val n=(top[2]*t+top[1])*t+top[0];val d=(bottom[2]*t+bottom[1])*t+bottom[0]
        require(n>0 && d>0 && n.isFinite() && d.isFinite())
        return (10*log10(n/d)).also {require(it.isFinite())}
    }
    fun maximum(lo:Double,hi:Double):Double=maxOf(gain(lo),gain(hi),roots.filter {it>lo && it<hi}.maxOfOrNull(::gain) ?: Double.NEGATIVE_INFINITY)
    fun response(w:Double):Double {
        fun magnitude(v:DoubleArray)=hypot(v[0]+v[1]*cos(w)+v[2]*cos(2*w),-v[1]*sin(w)-v[2]*sin(2*w))
        return 20*log10(magnitude(b)/magnitude(a))
    }
    /** H(e^jw) as (real, imaginary). */
    fun complex(w:Double):Pair<Double,Double> {
        val nr=b[0]+b[1]*cos(w)+b[2]*cos(2*w);val ni=-b[1]*sin(w)-b[2]*sin(2*w)
        val dr=a[0]+a[1]*cos(w)+a[2]*cos(2*w);val di=-a[1]*sin(w)-a[2]*sin(2*w)
        val den=dr*dr+di*di
        return Pair((nr*dr+ni*di)/den,(ni*dr-nr*di)/den)
    }
}

private fun quadraticRoots(a:Double,b:Double,c:Double):List<Double> {
    val scale=maxOf(abs(a),abs(b),abs(c))
    if(scale==0.0)return emptyList()
    val x=a/scale;val y=b/scale;val z=c/scale
    if(abs(x)<1e-14)return if(abs(y)<1e-14)emptyList() else listOf(-z/y)
    val discriminant=y*y-4*x*z
    if(discriminant<0)return emptyList()
    val q=-0.5*(y+Math.copySign(sqrt(discriminant),y))
    return if(q==0.0)listOf(-y/(2*x)) else listOf(q/x,z/q)
}
