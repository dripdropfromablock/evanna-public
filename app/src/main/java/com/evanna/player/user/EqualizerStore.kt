package com.evanna.player.user

import android.content.ContentValues
import com.evanna.player.equalizer.*
import com.truesample.usbaudio.EqBand
import org.json.JSONArray
import org.json.JSONObject

private fun encodeBands(config:EqualizerConfig):String=JSONArray().apply {
    for(band in config.bands)put(JSONObject().apply {put("type",band.type.name);put("frequency",band.frequencyHz.toDouble());put("gain",band.gainDb.toDouble())
        put("q",band.q.toDouble());put("channels",band.channels)})
}.toString()
/** Bands saved before per-band channels have four keys and play on both channels. */
private fun decodeConfig(text:String,preamp:Float,graphic:Boolean):EqualizerConfig {
    require(text.length<=16384)
    val array=JSONArray(text);require(array.length()<=MAX_EQ_BANDS)
    return EqualizerConfig(List(array.length()) {index ->
        val band=array.getJSONObject(index);val hasChannels=band.has("channels")
        require(band.length()==if(hasChannels)5 else 4)
        fun value(key:String)=(band.get(key) as? Number)?.toFloat() ?: error("Invalid stored EQ value")
        val channels=if(hasChannels)(band.get("channels") as? Int) ?: error("Invalid stored EQ channels") else 0
        EqBand(EqBand.Type.valueOf(band.getString("type")),value("frequency"),value("gain"),value("q"),channels)
    },preamp,graphic)
}
internal fun UserDatabase.equalizerPreset(id:Long,revision:Long?=null):EqualizerPreset? {
    require(id>=0 && (revision==null || revision>=1))
    return readableDatabase.rawQuery("SELECT name,preamp_db,bands_json,revision,graphic FROM eq_presets WHERE id=?",arrayOf(id.toString())).use {c ->
        if(!c.moveToFirst() || revision!=null && c.getLong(3)!=revision)null
        else EqualizerPreset(id,c.getString(0),decodeConfig(c.getString(2),c.getFloat(1),c.getInt(4).also {check(it in 0..1)}==1),c.getLong(3))
    }
}
internal fun UserDatabase.equalizerPresets():List<EqualizerPreset> = readableDatabase.rawQuery("SELECT id FROM eq_presets ORDER BY (id=0) DESC,modified DESC,id DESC LIMIT 202",null).use {c ->
    check(c.count<=201);buildList {while(c.moveToNext())add(checkNotNull(equalizerPreset(c.getLong(0))))}
}
internal fun UserDatabase.saveEqualizerPreset(id:Long?,name:String,config:EqualizerConfig,revision:Long?=null):EqualizerPreset?=try {
    val valid=checkNotNull(validEqualizerName(name));require(id==null || id>0)
    val db=writableDatabase;db.beginTransaction()
    try {
        val now=System.currentTimeMillis().coerceAtLeast(0)
        val values=ContentValues().apply {put("name",valid);put("preamp_db",config.preampDb);put("bands_json",encodeBands(config))
            put("graphic",if(config.graphic)1 else 0);put("modified",now)}
        val key=if(id==null) {
            require(revision==null)
            check(db.rawQuery("SELECT COUNT(*) FROM eq_presets WHERE id>0",null).use {it.moveToFirst();it.getInt(0)}<200)
            values.put("created",now);values.put("revision",1)
            db.insertOrThrow("eq_presets",null,values)
        }else {
            require(revision!=null && revision in 1 until Long.MAX_VALUE)
            values.put("revision",revision+1)
            check(db.update("eq_presets",values,"id=? AND revision=?",arrayOf(id.toString(),revision.toString()))==1);id
        }
        val result=checkNotNull(equalizerPreset(key))
        db.setTransactionSuccessful();result
    }finally {db.endTransaction()}
}catch(_:Exception){null}

internal fun UserDatabase.deleteEqualizerPreset(id:Long,revision:Long):Boolean=try {
    require(id>0 && revision>=1)
    val db=writableDatabase;db.beginTransaction()
    try {
        check(equalizerPreset(id,revision)!=null)
        db.execSQL("UPDATE dac_profiles SET eq_enabled=0,eq_preset_id=NULL WHERE eq_preset_id=?",arrayOf(id))
        check(db.delete("eq_presets","id=? AND revision=?",arrayOf(id.toString(),revision.toString()))==1)
        db.setTransactionSuccessful();true
    }finally {db.endTransaction()}
}catch(_:Exception){false}

internal fun UserDatabase.selectEqualizer(key:String,id:Long?,revision:Long?,enabled:Boolean):Boolean = updateProfile(key) {profile ->
    val preset=id?.let {checkNotNull(equalizerPreset(it,revision))}
    profile.copy(equalizer=preset,eqEnabled=enabled)
}

/** Preset edits and the active DAC link commit together; a stale editor never runs its mutation. */
internal fun UserDatabase.commitEqualizer(key:String,expectedId:Long?,expectedRevision:Long?,enabled:Boolean,
    change:()->EqualizerPreset?):EqualizerPreset?=try {
    val db=writableDatabase;db.beginTransaction()
    val saved=try {
        val before=checkNotNull(profile(key))
        check(before.equalizer?.id==expectedId && before.equalizer?.revision==expectedRevision)
        val preset=checkNotNull(change())
        check(selectEqualizer(key,preset.id,preset.revision,enabled))
        db.setTransactionSuccessful();preset
    }finally {db.endTransaction()}
    changed();saved
}catch(_:Exception){null}
