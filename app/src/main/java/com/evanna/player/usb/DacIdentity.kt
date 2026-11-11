package com.evanna.player.usb

import java.util.Locale

internal const val LAST_DAC_PREFERENCE="last_usb_dac"

internal data class DacIdentity(val vendorId:Int,val productId:Int,val productName:String) {
    init {
        require(vendorId in 0..65535 && productId in 0..65535)
        require(productName.length<=128 && productName==productName.trim() && productName.none {it.isISOControl()})
    }
    val key get()="$vendorId:$productId:${productName.lowercase(Locale.ROOT)}"
    val storedValue get()="$vendorId:$productId:$productName"
    val displayName get()=productName.ifEmpty {"USB DAC"}
}
internal data class DacConnection(val id:Int,val identity:DacIdentity,val permitted:Boolean)

internal fun dacIdentity(vendorId:Int,productId:Int,name:String?):DacIdentity =
    DacIdentity(vendorId,productId,name.orEmpty().filterNot {it.isISOControl()}.trim().take(128))

internal fun storedDacIdentity(value:String?):DacIdentity? {
    if(value==null || value.length>160)return null
    val fields=value.split(':',limit=3)
    if(fields.size!=3)return null
    return runCatching {DacIdentity(fields[0].toInt(),fields[1].toInt(),fields[2])}.getOrNull()
}
internal fun sameDac(first:DacIdentity?,second:DacIdentity?):Boolean = first!=null && second!=null && first.key==second.key

/** Settings switch: connect, and carry on playing, when a DAC that was used before is plugged in. On unless the owner turns it off. */
internal const val AUTO_CONNECT_DAC_PREFERENCE="auto_connect_dac"

/**
 * Whether a plugged-in DAC is connected without a tap. Only a DAC the owner has opened before qualifies: a new one has no
 * saved volume settings yet. Access is never asked for here; Android's own popup (or an earlier "always") grants it with the plug-in.
 */
internal fun shouldAutoConnect(enabled:Boolean,usedBefore:Boolean,permitted:Boolean,alreadyOpen:Boolean,busy:Boolean):Boolean =
    enabled && usedBefore && permitted && !alreadyOpen && !busy

internal fun preferredDacId(selected:Int?,connections:List<DacConnection>,last:DacIdentity?):Int? =
    connections.firstOrNull {it.id==selected}?.id ?: connections.firstOrNull {sameDac(it.identity,last)}?.id ?: connections.firstOrNull()?.id
