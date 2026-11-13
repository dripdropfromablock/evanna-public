package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.equalizer.*
import com.evanna.player.loudness.*
import com.evanna.player.user.*
import com.evanna.player.usb.dacIdentity
import java.io.File

/** Disposable personal-data v6 -> v7 migration; never opens the owner's user.db. */
internal object CrossfeedStoreProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="crossfeed-store-probe.db"
        val backup=File(context.noBackupFilesDir,"user-migrations/$name-v6.db");val blocker=File(backup,"keep")
        context.deleteDatabase(name);blocker.delete();backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,6) {
            override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true)}
            override fun onCreate(db:SQLiteDatabase){userSchema(6).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        val identity=dacIdentity(1,2,"Probe DAC");val id="a".repeat(64);val version="b".repeat(64)
        try {
            old.setWriteAheadLoggingEnabled(true)
            val db=old.writableDatabase
            db.execSQL("INSERT INTO metadata VALUES('library_sort:SONGS','ARTIST')")
            db.execSQL("INSERT INTO eq_presets VALUES(0,'Flat',0,'[]',0,0,1)")
            db.execSQL("INSERT INTO eq_presets VALUES(5,'Linked',-4,?,1,1,2)",arrayOf("[{\"type\":\"PEAK\",\"frequency\":1000,\"gain\":3,\"q\":1}]"))
            // The profile links preset 5, so the backup must copy presets before profiles.
            db.execSQL("INSERT INTO dac_profiles VALUES(?,1,2,'Probe DAC',96000,-30,-10,0.5,'ALBUM',1,5)",arrayOf(identity.key))
            db.execSQL("INSERT INTO playlists VALUES(1,'Retained',1,1,1)")
            db.execSQL("INSERT INTO playlist_items VALUES(1,0,?,'Fixture',NULL,NULL,1000000,NULL)",arrayOf(id))
            db.execSQL("INSERT INTO favorites VALUES(?,100)",arrayOf(id));db.execSQL("INSERT INTO history VALUES('event',?,200)",arrayOf(id))
            db.execSQL("INSERT INTO loudness VALUES(?,?,-23,0.2,1000000,300)",arrayOf(id,version))
            // A directory at the backup path stops the upgrade before any schema change.
            check(backup.mkdirs() && blocker.createNewFile())
            check(runCatching {UserDatabase(context,name).use {it.writableDatabase}}.isFailure)
            check(db.version==6)
            check(db.rawQuery("PRAGMA table_info(dac_profiles)",null).use {c -> var added=false;while(c.moveToNext())if(c.getString(1)=="crossfeed")added=true;!added})
            check(blocker.delete() && backup.delete())
            UserDatabase(context,name).use {store ->
                val profile=checkNotNull(store.profile(identity.key))
                check(profile.crossfeed==Crossfeed.OFF)
                check(profile.levelingMode==LevelingMode.ALBUM && profile.maxRate==96000 && profile.volumeLimitDb==-10f)
                check(store.isFavorite(id));check(store.playlist(1)?.name=="Retained")
                check(store.loudness(id,version)?.measurement==LoudnessMeasurement(-23.0,0.2,1_000_000))
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==6)
                    for((table,rows) in listOf("metadata" to 1,"eq_presets" to 2,"dac_profiles" to 1,"playlists" to 1,"playlist_items" to 1,"favorites" to 1,"history" to 1,"loudness" to 1))
                        check(copy.rawQuery("SELECT COUNT(*) FROM $table",null).use {it.moveToFirst();it.getInt(0)}==rows)
                    check(copy.rawQuery("SELECT eq_preset_id FROM dac_profiles",null).use {it.moveToFirst();it.getLong(0)}==5L)
                }
                check(store.updateProfile(identity.key){it.copy(crossfeed=Crossfeed.STRONG)})
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_profile BEFORE UPDATE ON dac_profiles BEGIN SELECT RAISE(ABORT,'test'); END")
                check(!store.updateProfile(identity.key){it.copy(crossfeed=Crossfeed.LIGHT)})
                check(store.profile(identity.key)?.crossfeed==Crossfeed.STRONG)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_profile")
            }
            UserDatabase(context,name).use {store ->
                check(store.readableDatabase.version==USER_DB_VERSION)
                check(store.profile(identity.key)?.crossfeed==Crossfeed.STRONG)
                store.writableDatabase.execSQL("UPDATE dac_profiles SET crossfeed='unknown'")
                check(store.profile(identity.key)?.crossfeed==Crossfeed.OFF)
            }
            return "Disposable v6 WAL migration with a blocked-then-retried private backup, preset-before-profile copy, full personal-data retention, and crossfeed default/save/failed-write/reopen/unknown-value checks pass. No owner data, grants or output touched."
        }finally {old.close();context.deleteDatabase(name);blocker.delete();backup.delete()}
    }
}
