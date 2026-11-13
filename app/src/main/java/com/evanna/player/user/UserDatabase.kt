package com.evanna.player.user

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.usb.DacIdentity
import com.evanna.player.storage.backupSqliteSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.evanna.player.loudness.storedLevelingMode
import com.evanna.player.equalizer.storedCrossfeed

internal const val USER_DB_VERSION=8

internal fun userSchema(version:Int):List<String> = listOf(
    "CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
    "CREATE TABLE dac_profiles(identity TEXT PRIMARY KEY,vendor_id INTEGER NOT NULL,product_id INTEGER NOT NULL,product_name TEXT NOT NULL,max_rate INTEGER,start_volume_db REAL NOT NULL,volume_limit_db REAL NOT NULL,key_step_db REAL NOT NULL)"
) + (if(version>=2)listOf(
    "CREATE TABLE playlists(id INTEGER PRIMARY KEY,name TEXT NOT NULL,created INTEGER NOT NULL,modified INTEGER NOT NULL,revision INTEGER NOT NULL)",
    "CREATE TABLE playlist_items(playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,position INTEGER NOT NULL,track_id TEXT,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER,file_name TEXT,PRIMARY KEY(playlist_id,position))"
)else emptyList()) + (if(version>=3)listOf("CREATE TABLE favorites(track_id TEXT PRIMARY KEY,added_at INTEGER NOT NULL)",
    "CREATE INDEX favorites_by_added ON favorites(added_at DESC,track_id)")else emptyList()) + (if(version>=4)listOf(
    "CREATE TABLE history(event_id TEXT PRIMARY KEY,track_id TEXT NOT NULL,played_at INTEGER NOT NULL)",
    "CREATE INDEX history_by_date ON history(played_at DESC)","CREATE INDEX history_by_track ON history(track_id,played_at DESC)")else emptyList()) +
    (if(version>=5)listOf("ALTER TABLE dac_profiles ADD COLUMN leveling_mode TEXT NOT NULL DEFAULT 'OFF'",
        "CREATE TABLE loudness(track_id TEXT PRIMARY KEY,source_key TEXT NOT NULL,integrated_lufs REAL,sample_peak REAL NOT NULL,duration_us INTEGER NOT NULL,measured_at INTEGER NOT NULL)")else emptyList())+
    (if(version>=6)listOf("ALTER TABLE dac_profiles ADD COLUMN eq_enabled INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE eq_presets(id INTEGER PRIMARY KEY,name TEXT NOT NULL,preamp_db REAL NOT NULL,bands_json TEXT NOT NULL,created INTEGER NOT NULL,modified INTEGER NOT NULL,revision INTEGER NOT NULL)",
        "ALTER TABLE dac_profiles ADD COLUMN eq_preset_id INTEGER REFERENCES eq_presets(id) ON DELETE SET NULL")else emptyList())+
    (if(version>=7)listOf("ALTER TABLE dac_profiles ADD COLUMN crossfeed TEXT NOT NULL DEFAULT 'OFF'")else emptyList())+
    if(version>=8)listOf("ALTER TABLE eq_presets ADD COLUMN graphic INTEGER NOT NULL DEFAULT 0")else emptyList()

/** Durable personal data is separate from the rebuildable file index. All calls run on an IO lane. */
internal class UserDatabase(context:Context,name:String="user.db"):SQLiteOpenHelper(context,name,null,USER_DB_VERSION) {
    private val mutableChanges=MutableStateFlow(0L)
    val changes=mutableChanges.asStateFlow()
    internal fun changed(){mutableChanges.update {it+1}}
    private val backupDirectory=File(context.noBackupFilesDir,"user-migrations")
    private val backupName=name
    init {setWriteAheadLoggingEnabled(true)}
    override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true)}
    override fun onCreate(db:SQLiteDatabase) {
        userSchema(USER_DB_VERSION).forEach(db::execSQL)
        seedEqualizerFlat(db)
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1 until USER_DB_VERSION && newVersion==USER_DB_VERSION){"Personal data migration required"}
        // Profiles reference EQ presets, so presets are copied first.
        backupSqliteSnapshot(db,File(backupDirectory,"$backupName-v$oldVersion.db"),userSchema(oldVersion),
            listOf("metadata" to "key")+(if(oldVersion>=6)listOf("eq_presets" to "id")else emptyList())+listOf("dac_profiles" to "identity")+
                (if(oldVersion>=2)listOf("playlists" to "id","playlist_items" to "playlist_id,position")else emptyList())+
                (if(oldVersion>=3)listOf("favorites" to "track_id")else emptyList())+
                (if(oldVersion>=4)listOf("history" to "event_id")else emptyList())+
                if(oldVersion>=5)listOf("loudness" to "track_id")else emptyList())
        userSchema(USER_DB_VERSION).drop(userSchema(oldVersion).size).forEach(db::execSQL)
        if(oldVersion<6)seedEqualizerFlat(db)
    }
    // The old USB-only EQ's tables and columns stay so every older database still migrates; nothing reads them now.
    private fun seedEqualizerFlat(db:SQLiteDatabase) {
        db.execSQL("INSERT INTO eq_presets(id,name,preamp_db,bands_json,created,modified,revision) VALUES(0,'Flat',0,'[]',0,0,1)")
    }

    fun profileFor(identity:DacIdentity,legacy:LegacyUsbSettings?):DacProfile {
        val db=writableDatabase;db.beginTransaction()
        try {
            readProfile(db,identity.key)?.let {db.setTransactionSuccessful();return it}
            val migrated=db.rawQuery("SELECT value FROM metadata WHERE key='jd1_profile_seeded'",null).use {it.moveToFirst()}
            val profile=initialDacProfile(identity,legacy.takeUnless {migrated})
            db.insertOrThrow("dac_profiles",null,values(profile))
            if(isKnownJd1(identity))db.insertWithOnConflict("metadata",null,ContentValues().apply {put("key","jd1_profile_seeded");put("value","1")},SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful();return profile
        }finally {db.endTransaction()}
    }

    fun profile(key:String):DacProfile?=readProfile(readableDatabase,key)

    fun updateProfile(key:String,change:(DacProfile)->DacProfile):Boolean = try {
        val db=writableDatabase;db.beginTransaction()
        try {
            val before=readProfile(db,key)
            if(before==null)false else {
                val after=change(before);require(after.identity.key==key)
                check(db.update("dac_profiles",values(after),"identity=?",arrayOf(key))==1)
                db.setTransactionSuccessful();true
            }
        }finally {db.endTransaction()}
    }catch(_:Exception){false}

    private fun readProfile(db:SQLiteDatabase,key:String):DacProfile? =
        db.rawQuery("SELECT vendor_id,product_id,product_name,max_rate,start_volume_db,volume_limit_db,key_step_db,leveling_mode,crossfeed FROM dac_profiles WHERE identity=?",arrayOf(key)).use {cursor ->
            if(!cursor.moveToFirst())null else {
                val identity=DacIdentity(cursor.getInt(0),cursor.getInt(1),cursor.getString(2));check(identity.key==key)
                DacProfile(identity,if(cursor.isNull(3))null else cursor.getInt(3),cursor.getFloat(4),cursor.getFloat(5),cursor.getFloat(6),storedLevelingMode(cursor.getString(7)),
                    storedCrossfeed(cursor.getString(8)))
            }
        }
    private fun values(profile:DacProfile)=ContentValues().apply {
        put("identity",profile.identity.key);put("vendor_id",profile.identity.vendorId);put("product_id",profile.identity.productId)
        put("product_name",profile.identity.productName);put("max_rate",profile.maxRate)
        put("start_volume_db",profile.startVolumeDb);put("volume_limit_db",profile.volumeLimitDb);put("key_step_db",profile.keyStepDb)
        put("leveling_mode",profile.levelingMode.name)
        put("crossfeed",profile.crossfeed.name)
    }
}

internal object UserData {
    @Volatile private var instance:UserDatabase?=null
    fun database(context:Context):UserDatabase=instance ?: synchronized(this) {
        instance ?: UserDatabase(context.applicationContext).also {instance=it}
    }
}
