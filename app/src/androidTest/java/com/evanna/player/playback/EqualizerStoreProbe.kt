package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.equalizer.*
import com.evanna.player.loudness.*
import com.evanna.player.user.*
import com.evanna.player.usb.dacIdentity
import com.truesample.usbaudio.EqBand
import java.io.File

internal object EqualizerStoreProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="equalizer-store-probe.db"
        val backup=File(context.noBackupFilesDir,"user-migrations/$name-v5.db")
        context.deleteDatabase(name);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,5) {
            override fun onCreate(db:SQLiteDatabase){userSchema(5).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        val identity=dacIdentity(1,2,"Probe DAC");val id="a".repeat(64);val version="b".repeat(64)
        val config=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,3f,1f)),-4f)
        try {
            old.setWriteAheadLoggingEnabled(true)
            val db=old.writableDatabase
            db.execSQL("INSERT INTO metadata VALUES('library_sort:SONGS','ARTIST')")
            db.execSQL("INSERT INTO dac_profiles VALUES(?,1,2,'Probe DAC',96000,-30,-10,0.5,'ALBUM')",arrayOf(identity.key))
            db.execSQL("INSERT INTO playlists VALUES(1,'Retained',1,1,1)")
            db.execSQL("INSERT INTO playlist_items VALUES(1,0,?,'Fixture',NULL,NULL,1000000,NULL)",arrayOf(id))
            db.execSQL("INSERT INTO favorites VALUES(?,100)",arrayOf(id));db.execSQL("INSERT INTO history VALUES('event',?,200)",arrayOf(id))
            db.execSQL("INSERT INTO loudness VALUES(?,?,-23,0.2,1000000,300)",arrayOf(id,version))
            db.execSQL("CREATE TABLE eq_presets(conflict TEXT)")
            check(runCatching {UserDatabase(context,name).use {it.writableDatabase}}.isFailure)
            check(db.version==5)
            check(db.rawQuery("PRAGMA table_info(dac_profiles)",null).use {c -> var added=false;while(c.moveToNext())if(c.getString(1)=="eq_enabled")added=true;!added})
            db.execSQL("DROP TABLE eq_presets")
            var savedId=0L
            UserDatabase(context,name).use {store ->
                check(store.profile(identity.key)?.let {!it.eqEnabled && it.equalizer==null && it.levelingMode==LevelingMode.ALBUM && it.volumeLimitDb==-10f}==true)
                check(store.isFavorite(id));check(store.playlist(1)?.name=="Retained")
                check(store.personalPage(PersonalView.MOST).single()==PersonalSong(id,1))
                check(store.loudness(id,version)?.measurement==LoudnessMeasurement(-23.0,0.2,1_000_000))
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==5)
                    for(table in listOf("metadata","dac_profiles","playlists","playlist_items","favorites","history","loudness"))
                        check(copy.rawQuery("SELECT COUNT(*) FROM $table",null).use {it.moveToFirst();it.getInt(0)}==1)
                }
                val flat=checkNotNull(store.equalizerPreset(0));check(flat.name=="Flat" && flat.config==EqualizerConfig())
                check(store.saveEqualizerPreset(0,"Replaced",config,1)==null);check(!store.deleteEqualizerPreset(0,1))
                val preset=checkNotNull(store.saveEqualizerPreset(null,"Fixture",config));savedId=preset.id
                check(store.equalizerPreset(preset.id)==preset)
                check(store.selectEqualizer(identity.key,preset.id,preset.revision,true))
                check(store.profile(identity.key)?.equalizer==preset)
                val updated=checkNotNull(store.saveEqualizerPreset(preset.id,"Renamed",config.copy(preampDb=-5f),preset.revision))
                check(updated.revision==2L);check(store.saveEqualizerPreset(preset.id,"Stale",config,1)==null)
                check(!store.selectEqualizer(identity.key,preset.id,1,true));check(!store.deleteEqualizerPreset(preset.id,1))
                check(store.profile(identity.key)?.equalizer==updated)
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_eq BEFORE UPDATE ON eq_presets WHEN OLD.id>0 BEGIN SELECT RAISE(ABORT,'test'); END")
                check(store.saveEqualizerPreset(preset.id,"Refused",config,2)==null)
                check(store.equalizerPreset(preset.id)==updated);store.writableDatabase.execSQL("DROP TRIGGER refuse_eq")
                var called=false
                check(store.commitEqualizer(identity.key,preset.id,1,true){called=true;updated}==null && !called)
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_link BEFORE UPDATE ON dac_profiles BEGIN SELECT RAISE(ABORT,'test'); END")
                check(store.commitEqualizer(identity.key,preset.id,2,true){store.saveEqualizerPreset(preset.id,"Must roll back",config,2)}==null)
                check(store.equalizerPreset(preset.id)==updated)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_link")
                check(store.commitEqualizer(identity.key,preset.id,2,false){updated}==updated)
                check(store.profile(identity.key)?.eqEnabled==false)
                check(store.commitEqualizer(identity.key,preset.id,2,true){updated}==updated)
            }
            UserDatabase(context,name).use {store ->
                check(store.profile(identity.key)?.eqEnabled==true)
                check(store.profile(identity.key)?.equalizer?.name=="Renamed")
                check(store.deleteEqualizerPreset(savedId,2))
                check(store.profile(identity.key)?.let {!it.eqEnabled && it.equalizer==null}==true)
                val preset=checkNotNull(store.saveEqualizerPreset(null,"Bad fixture",config))
                for(json in listOf("not json","[{\"type\":\"PEAK\",\"frequency\":1000,\"gain\":99,\"q\":1}]",
                    "[{\"type\":\"PEAK\",\"frequency\":\"1000\",\"gain\":3,\"q\":1}]","[{\"type\":\"PEAK\",\"frequency\":1000,\"gain\":3,\"q\":1,\"channels\":3}]",
                    "[{\"type\":\"PEAK\",\"frequency\":1000,\"gain\":3,\"q\":1,\"channels\":1.5}]")) {
                    store.writableDatabase.execSQL("UPDATE eq_presets SET bands_json=? WHERE id=?",arrayOf(json,preset.id))
                    check(runCatching {store.equalizerPreset(preset.id)}.isFailure)
                }
            }
            return "Disposable v5 WAL migration/rollback/retry/backup, complete personal-data retention, Flat/preset/profile/revision/reopen/failed-write/deletion and malformed JSON checks pass. No owner data, grants or output touched."
        }finally {old.close();context.deleteDatabase(name);backup.delete()}
    }
}
