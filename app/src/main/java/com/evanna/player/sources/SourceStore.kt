package com.evanna.player.sources

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal enum class SourceKind(val label:String) {
    HTTP("Audio URL"),UPNP("UPnP / DLNA"),PLEX("Plex"),JELLYFIN("Jellyfin"),SUBSONIC("Navidrome / OpenSubsonic"),
    WEBDAV("WebDAV"),SMB("SMB share"),RADIO("Internet radio"),WEB("Web downloads")
}
internal class SourceConnection(val id:String,val kind:SourceKind,val name:String,val config:JSONObject,val enabled:Boolean)
internal class SourceItem(val ref:SourceReference,val title:String,val artist:String?,val album:String?,val durationUs:Long?,
    val folder:Boolean,val details:JSONObject,val offlineFile:String?)

/** Server locations and item resolution data stay private; queues contain only SourceReference. */
internal class SourceStore(context:Context,name:String="sources.db"):SQLiteOpenHelper(context,name,null,1) {
    private val crypto=SourceSecrets()
    private val mutableChanges=MutableStateFlow(0L)
    val changes=mutableChanges.asStateFlow()
    init {setWriteAheadLoggingEnabled(true)}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE connections(id TEXT PRIMARY KEY,kind TEXT NOT NULL,name TEXT NOT NULL,identity_hash TEXT NOT NULL,config BLOB NOT NULL,enabled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,connection_id TEXT NOT NULL,identity_hash TEXT NOT NULL,parent_hash TEXT NOT NULL,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER,folder INTEGER NOT NULL,details BLOB NOT NULL,offline_file TEXT,UNIQUE(connection_id,identity_hash))")
        db.execSQL("CREATE INDEX source_items_parent ON items(connection_id,parent_hash,title,id)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Source migration required")
    fun connections():List<SourceConnection> = readableDatabase.rawQuery("SELECT id,kind,name,config,enabled FROM connections ORDER BY enabled DESC,name,id",null).use {cursor ->
        buildList {
            while(cursor.moveToNext()) {
                val id=cursor.getString(0)
                val config=try {decode("$id:connection",cursor.getBlob(3))}catch(_:IOException){null}
                add(SourceConnection(id,SourceKind.valueOf(cursor.getString(1)),cursor.getString(2),
                    config ?: JSONObject(),config!=null && cursor.getInt(4)==1))
            }
        }
    }
    fun connection(id:String):SourceConnection? = readableDatabase.rawQuery("SELECT kind,name,config,enabled FROM connections WHERE id=?",arrayOf(id)).use {c ->
        if(!c.moveToFirst())null else SourceConnection(id,SourceKind.valueOf(c.getString(0)),c.getString(1),decode("$id:connection",c.getBlob(2)),c.getInt(3)==1)
    }
    fun saveConnection(kind:SourceKind,name:String,config:JSONObject,id:String=UUID.randomUUID().toString()):SourceConnection {
        SourceReference(id,id)
        require((kind == SourceKind.WEB) == (id == WEB_PLAYBACK_CONNECTION_ID || id == SEARCH_CONNECTION_ID))
        require(name.isNotBlank() && name.length<=120 && name.none(Char::isISOControl))
        require(config.toString().toByteArray().size<=65536)
        val identity=hash(kind.name+"\n"+config.optString("serverId").ifBlank {config.optString("url").substringBefore('?').substringBefore('#')}+
            "\n"+config.optString("username")+"\n"+config.optString("accountId"))
        val db=writableDatabase;db.beginTransaction()
        try {
            db.rawQuery("SELECT identity_hash FROM connections WHERE id=?",arrayOf(id)).use {c ->
                if(c.moveToFirst())require(c.getString(0)==identity){"CONNECTION_IDENTITY_CHANGED"}
            }
            db.insertWithOnConflict("connections",null,ContentValues().apply {
                put("id",id);put("kind",kind.name);put("name",name);put("identity_hash",identity)
                put("config",encode("$id:connection",config));put("enabled",1)
            },SQLiteDatabase.CONFLICT_REPLACE).also {check(it!=-1L)}
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        mutableChanges.update {it+1};return checkNotNull(connection(id))
    }
    fun saveItem(connectionId:String,providerId:String,title:String,artist:String?=null,album:String?=null,durationUs:Long?=null,
        folder:Boolean=false,parentId:String="",details:JSONObject=JSONObject()):SourceItem {
        require(providerId.length in 1..8192 && providerId.none(Char::isISOControl))
        require(title.length in 1..512 && title.none(Char::isISOControl) && (artist?.length ?: 0)<=512 && (album?.length ?: 0)<=512)
        require(durationUs==null || durationUs>=0)
        require(details.toString().toByteArray().size<=1024*1024)
        val identity=hash(providerId);val db=writableDatabase;db.beginTransaction()
        val id:String
        try {
            check(db.rawQuery("SELECT enabled FROM connections WHERE id=?",arrayOf(connectionId)).use {it.moveToFirst() && it.getInt(0)==1}) {
                "CONNECTION_UNAVAILABLE"
            }
            id=db.rawQuery("SELECT id FROM items WHERE connection_id=? AND identity_hash=?",arrayOf(connectionId,identity)).use {c ->
                if(c.moveToFirst())c.getString(0)else UUID.randomUUID().toString()
            }
            val previous=item(SourceReference(connectionId,id))
            require(previous?.details?.optString("_key").isNullOrEmpty() || previous!!.details.getString("_key")==providerId)
            val stored=JSONObject(details.toString()).put("_key",providerId)
            db.insertWithOnConflict("items",null,ContentValues().apply {
                put("id",id);put("connection_id",connectionId);put("identity_hash",identity);put("parent_hash",hash(parentId))
                put("title",title);put("artist",artist);put("album",album);put("duration_us",durationUs);put("folder",if(folder)1 else 0)
                put("details",encode("$connectionId:$id:item",stored));put("offline_file",previous?.offlineFile)
            },SQLiteDatabase.CONFLICT_REPLACE).also {check(it!=-1L)}
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        mutableChanges.update {it+1};return checkNotNull(item(SourceReference(connectionId,id)))
    }
    fun item(ref:SourceReference):SourceItem? = readableDatabase.rawQuery(
        "SELECT title,artist,album,duration_us,folder,details,offline_file FROM items WHERE id=? AND connection_id=?",arrayOf(ref.itemId,ref.connectionId)).use {c ->
        if(!c.moveToFirst())null else SourceItem(ref,c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getLong(3),
            c.getInt(4)==1,decode("${ref.encoded}:item",c.getBlob(5)),c.getString(6))
    }
    fun items(connectionId:String,parentId:String=""):List<SourceItem> = readableDatabase.rawQuery(
        "SELECT id FROM items WHERE connection_id=? AND parent_hash=? ORDER BY title,id LIMIT 1000",arrayOf(connectionId,hash(parentId))).use {c ->
        buildList {while(c.moveToNext())item(SourceReference(connectionId,c.getString(0)))?.let(::add)}
    }
    fun updateDetails(ref:SourceReference,change:(JSONObject)->JSONObject) {
        val before=checkNotNull(item(ref));val next=change(JSONObject(before.details.toString()))
        require(next.toString().toByteArray().size<=1024*1024)
        check(writableDatabase.update("items",ContentValues().apply {put("details",encode("${ref.encoded}:item",next))},
            "id=? AND connection_id=?",arrayOf(ref.itemId,ref.connectionId))==1)
        mutableChanges.update {it+1}
    }
    fun setOffline(ref:SourceReference,fileName:String?) {
        require(fileName==null || fileName.matches(Regex("[0-9a-f-]{36}\\.audio")))
        check(writableDatabase.update("items",ContentValues().apply {put("offline_file",fileName)},"id=? AND connection_id=?",arrayOf(ref.itemId,ref.connectionId))==1)
        mutableChanges.update {it+1}
    }
    fun offlineNames():Set<String> = readableDatabase.rawQuery("SELECT offline_file FROM items WHERE offline_file IS NOT NULL",null).use {cursor ->
        buildSet {while(cursor.moveToNext())add(cursor.getString(0))}
    }
    fun offlineItems(connectionId:String):List<SourceItem> = readableDatabase.rawQuery(
        "SELECT id FROM items WHERE connection_id=? AND offline_file IS NOT NULL ORDER BY title,id LIMIT 1000",arrayOf(connectionId)).use {cursor ->
        buildList {while(cursor.moveToNext())item(SourceReference(connectionId,cursor.getString(0)))?.let(::add)}
    }
    fun forgetConnection(id:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val keepItems=db.rawQuery("SELECT kind FROM connections WHERE id=?",arrayOf(id)).use {cursor ->
                cursor.moveToFirst() && cursor.getString(0) !in setOf(SourceKind.HTTP.name,SourceKind.RADIO.name)
            }
            db.update("connections",ContentValues().apply {put("enabled",0);put("config",encode("$id:connection",JSONObject()))},"id=?",arrayOf(id))
            // Server IDs remain usable on reconnect. Direct links can contain signed query data.
            if(!keepItems)db.rawQuery("SELECT id FROM items WHERE connection_id=?",arrayOf(id)).use {c ->
                while(c.moveToNext()) {
                    val itemId=c.getString(0)
                    db.update("items",ContentValues().apply {put("details",encode("$id:$itemId:item",JSONObject()))},"id=?",arrayOf(itemId))
                }
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        mutableChanges.update {it+1}
    }
    private fun encode(aad:String,json:JSONObject)=crypto.encrypt(aad,json.toString().toByteArray(Charsets.UTF_8))
    private fun decode(aad:String,bytes:ByteArray)=try {JSONObject(crypto.decrypt(aad,bytes).toString(Charsets.UTF_8))}
        catch(_:Exception){throw IOException("CONNECTION_LOGIN_REQUIRED")}
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

internal class SourceSecrets {
    private val alias="evanna.sources.aes.v1"
    @Synchronized private fun key():SecretKey {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (keys.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(aad:String,plain:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad.toByteArray())
        check(cipher.iv.size==12);return byteArrayOf(1)+cipher.iv+cipher.doFinal(plain)
    }
    fun decrypt(aad:String,bytes:ByteArray):ByteArray {
        require(bytes.size in 29..2*1024*1024 && bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)));cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(bytes,13,bytes.size-13)
    }
}

internal object Sources {
    @Volatile private var instance:SourceStore?=null
    fun store(context:Context):SourceStore=instance ?: synchronized(this) {
        instance ?: SourceStore(context.applicationContext).also {instance=it}
    }
}
