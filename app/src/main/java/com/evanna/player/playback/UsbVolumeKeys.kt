package com.evanna.player.playback

import com.truesample.usbaudio.VolumeRange

internal data class UsbKeyContext(val generation:Long,val gain:VolumeRange,val keyStepDb:Float) {
    fun controlsMatch(other:UsbKeyContext):Boolean=generation==other.generation && keyStepDb==other.keyStepDb &&
        gain.minDb==other.gain.minDb && gain.maxDb==other.gain.maxDb && gain.stepDb==other.gain.stepDb
    fun valid():Boolean=generation>=0 && validUsbKeyStep(keyStepDb)!=null && keyVolumeScale(gain)!=null
}
internal data class UsbKeyRequest(val id:Long,val generation:Long,val db:Float)

/** Activity-thread state: one active write and one latest target, never a queue of per-repeat writes. */
internal class UsbVolumeKeys {
    private var context:UsbKeyContext?=null
    private var active:UsbKeyRequest?=null
    private var readback:UsbKeyContext?=null
    private var observed:UsbKeyContext?=null
    private var pending:Float?=null
    private var sequence=0L

    fun press(now:UsbKeyContext,direction:Int):UsbKeyRequest? {
        if(direction !in listOf(-1,1))return null
        if(!now.valid()){reset();return null}
        if(context?.controlsMatch(now)!=true)reset()
        if(context==null){context=now;observed=now}
        val base=pending ?: active?.db ?: checkNotNull(context).gain.currentDb
        pending=now.gain.copy(currentDb=base).adjusted(direction,now.keyStepDb) ?: return null
        return next(checkNotNull(context).gain.currentDb)
    }
    fun isCurrent(request:UsbKeyRequest):Boolean=active==request
    fun confirmedGain(now:UsbKeyContext):Float=(readback ?: context)?.takeIf {it.controlsMatch(now)}?.gain?.currentDb ?: now.gain.currentDb
    fun observe(now:UsbKeyContext?):UsbKeyRequest? {
        val before=context
        if(now==null || !now.valid() || before?.controlsMatch(now)==false ||
            (active!=null && now.gain.currentDb!=before?.gain?.currentDb && now.gain.currentDb!=active?.db)) {reset();return null}
        observed=now
        if(active==null)context=now
        return finish()
    }
    fun complete(request:UsbKeyRequest,confirmed:UsbKeyContext?):UsbKeyRequest? {
        if(!isCurrent(request))return null
        if(confirmed==null || !confirmed.valid() || context?.controlsMatch(confirmed)!=true || confirmed.gain.currentDb!=request.db) {
            reset();return null
        }
        readback=confirmed
        return finish()
    }
    /** Keep this request active until both reply and UI agree; an old intermediate UI value cannot undo a later reply. */
    private fun finish():UsbKeyRequest? {
        val confirmed=readback ?: return null
        val ui=observed ?: return null
        if(!confirmed.controlsMatch(ui) || confirmed.gain.currentDb!=ui.gain.currentDb)return null
        active=null;readback=null;context=confirmed
        return next(confirmed.gain.currentDb)
    }
    fun reset() {context=null;active=null;pending=null;readback=null;observed=null}
    private fun next(confirmed:Float):UsbKeyRequest? {
        if(active!=null)return null
        val target=pending ?: return null
        pending=null
        if(target==confirmed)return null
        return UsbKeyRequest(++sequence,checkNotNull(context).generation,target).also {active=it}
    }
}
