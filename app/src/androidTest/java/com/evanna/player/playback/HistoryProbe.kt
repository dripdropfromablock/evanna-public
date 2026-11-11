package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.user.*
import java.io.File

/** Only generated history in a disposable database; no real listening data or playback. */
internal object HistoryProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="history-probe.db"
        val backup=File(context.noBackupFilesDir,"user-migrations/$name-v3.db")
        val a="a".repeat(64);val b="b".repeat(64);val c="c".repeat(64)
        context.deleteDatabase(name);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,3) {
            override fun onCreate(db:SQLiteDatabase){userSchema(3).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        try {
            old.setWriteAheadLoggingEnabled(true)
            old.writableDatabase.execSQL("INSERT INTO favorites VALUES(?,1)",arrayOf(a))
            old.writableDatabase.execSQL("INSERT INTO playlists VALUES(1,'Retained',1,1,1)")
            UserDatabase(context,name).use {store ->
                check(store.isFavorite(a));check(store.playlist(1)?.name=="Retained")
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==3)
                    check(copy.rawQuery("SELECT track_id FROM favorites",null).use {it.moveToFirst() && it.getString(0)==a})
                }
                val batch=listOf(PlayedSong("one",a,1),PlayedSong("two",a,2),PlayedSong("three",b,3))
                check(store.saveHistory(batch));check(store.saveHistory(batch))
                val most=store.personalPage(PersonalView.MOST)
                check(most==listOf(PersonalSong(a,2),PersonalSong(b,1)))
                check(store.personalPage(PersonalView.RECENT).map {it.id}==listOf(b,a))
                check(store.personalWindow(PersonalView.MOST,b)==listOf(b))
                check(store.personalWindow(PersonalView.RECENT,a)==listOf(a))
                check(store.personalWindow(PersonalView.RECENT,c).isEmpty())
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_history BEFORE INSERT ON history WHEN NEW.event_id='refused' BEGIN SELECT RAISE(ABORT,'test'); END")
                check(!store.saveHistory(listOf(PlayedSong("partial",a,4),PlayedSong("refused",b,5))))
                check(store.personalPage(PersonalView.MOST)==most)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_history")
                check(store.saveHistory(List(5000){PlayedSong("generated-$it",if(it%2==0)a else c,10000L+it)}))
                check(store.personalPage(PersonalView.MOST)==listOf(PersonalSong(c,2500),PersonalSong(a,2500)))
                check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM history",null).use {it.moveToFirst();it.getInt(0)}==5000)
            }
            UserDatabase(context,name).use {store ->
                check(store.personalPage(PersonalView.MOST).sumOf {it.plays}==5000L)
                check(store.isFavorite(a));check(store.playlist(1)?.name=="Retained")
            }
            return "Private WAL migration/backup, favorite/playlist retention, history idempotence, recent/count ranking/anchors, failed-batch rollback, 5,000-play cap and reopen passed. No real playback or history changed."
        }finally {old.close();context.deleteDatabase(name);backup.delete()}
    }
}
