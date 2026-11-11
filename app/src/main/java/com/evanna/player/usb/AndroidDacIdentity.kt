package com.evanna.player.usb

import android.hardware.usb.UsbDevice
import android.content.Context

internal fun UsbDevice.dacIdentity():DacIdentity = dacIdentity(vendorId,productId,runCatching {productName}.getOrNull())

internal fun rememberDac(context:Context,identity:DacIdentity):Boolean {
    val preferences=context.getSharedPreferences("playback-settings",Context.MODE_PRIVATE)
    val previous=preferences.all[LAST_DAC_PREFERENCE] as? String
    val saved=runCatching {preferences.edit().putString(LAST_DAC_PREFERENCE,identity.storedValue).commit()}.getOrDefault(false)
    if(!saved)runCatching {preferences.edit().putString(LAST_DAC_PREFERENCE,previous).commit()}
    return saved
}
