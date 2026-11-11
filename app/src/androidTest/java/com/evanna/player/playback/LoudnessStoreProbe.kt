package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.loudness.*
import com.evanna.player.user.*
import com.evanna.player.usb.dacIdentity
import java.io.File

internal object LoudnessStoreProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="loudness-store-probe.db"
        val backup=File(context.noBackupFilesDir,"user-migrations/$name-v4.db")
        context.deleteDatabase(name);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,4) {
            override fun onCreate(db:SQLiteDatabase){userSchema(4).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        val id="a".repeat(64);val key="b".repeat(64);val newer="c".repeat(64)
        val identity=dacIdentity(1,2,"Probe DAC")
        try {
            old.setWriteAheadLoggingEnabled(true)
            val db=old.writableDatabase
            db.execSQL("INSERT INTO metadata VALUES('library_sort:SONGS','ARTIST')")
            db.execSQL("INSERT INTO dac_profiles VALUES(?,1,2,'Probe DAC',96000,-30,-10,0.5)",arrayOf(identity.key))
            db.execSQL("INSERT INTO playlists VALUES(1,'Retained',1,1,1)")
            db.execSQL("INSERT INTO playlist_items VALUES(1,0,?,'Fixture',NULL,NULL,1000000,NULL)",arrayOf(id))
            db.execSQL("INSERT INTO favorites VALUES(?,100)",arrayOf(id))
            db.execSQL("INSERT INTO history VALUES('event',?,200)",arrayOf(id))
            db.execSQL("CREATE TABLE loudness(conflict TEXT)")
            check(runCatching {UserDatabase(context,name).use {it.writableDatabase}}.isFailure)
            check(db.version==4)
            check(db.rawQuery("PRAGMA table_info(dac_profiles)",null).use {c ->var found=false;while(c.moveToNext())if(c.getString(1)=="leveling_mode")found=true;!found})
            db.execSQL("DROP TABLE loudness")
            UserDatabase(context,name).use {store ->
                check(store.readableDatabase.version==USER_DB_VERSION)
                check(store.profile(identity.key)?.let {it.levelingMode==LevelingMode.OFF && it.volumeLimitDb==-10f && it.maxRate==96000}==true)
                check(store.isFavorite(id));check(store.playlist(1)?.name=="Retained")
                check(store.personalPage(PersonalView.MOST).single()==PersonalSong(id,1))
                check(store.librarySort(com.evanna.player.library.LibrarySortScope.SONGS)==com.evanna.player.library.LibrarySort.ARTIST)
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==4)
                    for(table in listOf("metadata","dac_profiles","playlists","playlist_items","favorites","history"))
                        check(copy.rawQuery("SELECT COUNT(*) FROM $table",null).use {it.moveToFirst();it.getInt(0)}==1)
                }
                val record=LoudnessRecord(id,key,LoudnessMeasurement(-23.0,0.2,1_000_000),300)
                check(store.saveLoudness(record));check(store.loudness(id,key)==record);check(store.loudness(id,newer)==null)
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_loudness BEFORE INSERT ON loudness BEGIN SELECT RAISE(ABORT,'test'); END")
                check(!store.saveLoudness(record.copy(sourceKey=newer)));check(store.loudness(id,key)==record)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_loudness")
                check(store.saveLoudness(record.copy(sourceKey=newer,measurement=LoudnessMeasurement(null,0.0,1_000_000))))
                check(store.loudness(id,key)==null && store.loudness(id,newer)?.measurement?.integratedLufs==null)
                check(store.updateProfile(identity.key){it.copy(levelingMode=LevelingMode.ALBUM)})
                check(store.profile(identity.key)?.levelingMode==LevelingMode.ALBUM)
            }
            UserDatabase(context,name).use {store ->
                check(store.profile(identity.key)?.levelingMode==LevelingMode.ALBUM)
                check(store.loudness(id,newer)?.measurement?.samplePeak==0.0)
                store.writableDatabase.execSQL("UPDATE dac_profiles SET leveling_mode='unknown'")
                check(store.profile(identity.key)?.levelingMode==LevelingMode.OFF)
            }
            return "Disposable user v4 WAL backup/rollback/retry, all personal-data retention, mode defaults/reopen, versioned quiet cache and failed-save preservation pass. No owner data, files, grants or audio touched."
        }finally {old.close();context.deleteDatabase(name);backup.delete()}
    }
}
