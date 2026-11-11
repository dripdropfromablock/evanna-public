package com.evanna.player.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Called inside an upgrade transaction: logical rows include committed WAL data and never leave app storage. */
internal fun backupSqliteSnapshot(source:SQLiteDatabase,destination:File,schema:List<String>,tables:List<Pair<String,String>>) {
    check(source.inTransaction() && source.version>0){"DATABASE_BACKUP_SNAPSHOT_REQUIRED"}
    val parent=checkNotNull(destination.parentFile)
    check(parent.isDirectory || parent.mkdirs()){"DATABASE_BACKUP_DIRECTORY_FAILED"}
    val staging=File(parent,destination.name+".staging")
    SQLiteDatabase.deleteDatabase(staging)
    try {
        SQLiteDatabase.openOrCreateDatabase(staging,null).use { target ->
            target.setForeignKeyConstraintsEnabled(true);target.beginTransaction()
            try {
                schema.forEach(target::execSQL)
                for((table,order) in tables)source.rawQuery("SELECT * FROM $table ORDER BY $order",null).use {cursor ->
                    while(cursor.moveToNext()) {
                        val row=ContentValues()
                        for(i in 0 until cursor.columnCount)when(cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL->row.putNull(cursor.getColumnName(i))
                            Cursor.FIELD_TYPE_INTEGER->row.put(cursor.getColumnName(i),cursor.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT->row.put(cursor.getColumnName(i),cursor.getDouble(i))
                            Cursor.FIELD_TYPE_BLOB->row.put(cursor.getColumnName(i),cursor.getBlob(i))
                            else->row.put(cursor.getColumnName(i),cursor.getString(i))
                        }
                        target.insertOrThrow(table,null,row)
                    }
                }
                target.version=source.version;target.setTransactionSuccessful()
            }finally {target.endTransaction()}
        }
        SQLiteDatabase.openDatabase(staging.path,null,SQLiteDatabase.OPEN_READONLY).use {target ->
            check(target.rawQuery("PRAGMA quick_check",null).use {it.moveToFirst() && it.getString(0)=="ok"})
            check(target.rawQuery("PRAGMA foreign_key_check",null).use {!it.moveToFirst()})
            for((table,order) in tables) {
                val query="SELECT * FROM $table ORDER BY $order"
                check(tableDigest(source,query).contentEquals(tableDigest(target,query))){"DATABASE_BACKUP_VERIFY_FAILED"}
            }
        }
        FileOutputStream(staging,true).use {it.fd.sync()}
        Os.rename(staging.path,destination.path)
        val directory=Os.open(parent.path,OsConstants.O_RDONLY,0)
        try {Os.fsync(directory)}finally {Os.close(directory)}
    }finally {SQLiteDatabase.deleteDatabase(staging)}
}

private fun tableDigest(db:SQLiteDatabase,query:String):ByteArray {
    val digest=MessageDigest.getInstance("SHA-256")
    db.rawQuery(query,null).use {cursor ->
        cursor.columnNames.forEach {digest.update(it.toByteArray(Charsets.UTF_8));digest.update(0.toByte())}
        while(cursor.moveToNext())for(i in 0 until cursor.columnCount) {
            val type=cursor.getType(i);digest.update(type.toByte())
            val bytes=when(type) {
                Cursor.FIELD_TYPE_NULL->byteArrayOf()
                Cursor.FIELD_TYPE_BLOB->cursor.getBlob(i)
                else->cursor.getString(i).toByteArray(Charsets.UTF_8)
            }
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
    }
    return digest.digest()
}
