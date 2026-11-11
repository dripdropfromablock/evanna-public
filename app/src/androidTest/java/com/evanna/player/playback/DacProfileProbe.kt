package com.evanna.player.playback

import android.app.Instrumentation
import com.evanna.player.usb.DacIdentity
import com.evanna.player.user.*

/** Uses one disposable personal database; never opens audio or changes the owner's settings. */
internal object DacProfileProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val name="dac-profile-probe.db"
        val jd1=DacIdentity(0x31b2,0x3214,"FIIO JD1")
        val other=DacIdentity(jd1.vendorId,jd1.productId,"Another DAC")
        val legacy=LegacyUsbSettings(false,-17f,-9f,2f)
        context.deleteDatabase(name)
        try {
            UserDatabase(context,name).use {store ->
                check(store.profileFor(other,legacy)==DacProfile(other))
                val migrated=store.profileFor(jd1,legacy)
                check(migrated==DacProfile(jd1,48000,-17f,-9f,2f))
                check(store.profileFor(jd1,legacy.copy(followSongRate=true))==migrated)
                check(store.updateProfile(jd1.key){it.copy(maxRate=96000)})
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_profile BEFORE UPDATE ON dac_profiles BEGIN SELECT RAISE(ABORT,'test'); END")
                check(!store.updateProfile(jd1.key){it.copy(startVolumeDb=-1f)})
                check(store.profile(jd1.key)==migrated.copy(maxRate=96000))
                store.writableDatabase.execSQL("DROP TRIGGER refuse_profile")
                check(!store.updateProfile("missing"){it})
            }
            UserDatabase(context,name).use {store ->
                check(store.profile(jd1.key)==DacProfile(jd1,96000,-17f,-9f,2f))
                check(store.profile(other.key)==DacProfile(other))
            }
            return "Private profile creation, exact JD1 migration, same-ID different-product isolation, reopen and failed-write rollback passed; no real settings or output changed."
        }finally {context.deleteDatabase(name)}
    }
}
