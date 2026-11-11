package com.evanna.player.user

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.evanna.player.loudness.*

/** A rescan's source version must match; a present record with null LUFS is still a measured result. */
internal fun UserDatabase.loudness(id:String,sourceKey:String):LoudnessRecord? {
    require(validLoudnessKey(id) && validLoudnessKey(sourceKey))
    return readableDatabase.rawQuery("SELECT integrated_lufs,sample_peak,duration_us,measured_at FROM loudness WHERE track_id=? AND source_key=?",arrayOf(id,sourceKey)).use {c ->
        if(!c.moveToFirst())null else LoudnessRecord(id,sourceKey,LoudnessMeasurement(if(c.isNull(0))null else c.getDouble(0),c.getDouble(1),c.getLong(2)),c.getLong(3))
    }
}
internal fun UserDatabase.saveLoudness(record:LoudnessRecord):Boolean = try {
    val values=ContentValues().apply {
        put("track_id",record.trackId);put("source_key",record.sourceKey);put("integrated_lufs",record.measurement.integratedLufs)
        put("sample_peak",record.measurement.samplePeak);put("duration_us",record.measurement.durationUs);put("measured_at",record.measuredAt)
    }
    writableDatabase.insertWithOnConflict("loudness",null,values,SQLiteDatabase.CONFLICT_REPLACE)>=0
}catch(_:Exception){false}
