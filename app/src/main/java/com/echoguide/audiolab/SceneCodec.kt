package com.echoguide.audiolab
import org.json.JSONObject
import org.json.JSONArray
data class SavedScene(val g:GridConfig,val scene:SceneConfig,val s:ScanSettings,val cells:List<Cell>)
object SceneCodec {
    fun encode(g:GridConfig,c:SceneConfig,s:ScanSettings,cells:List<Cell>):String{
        val j=JSONObject()
        j.put("version",4)
        j.put("grid",JSONObject().put("rows",g.rows).put("cols",g.cols).put("hf",g.hf).put("vf",g.vf).put("az",g.az).put("el",g.el).put("path",g.path.name))
        j.put("scene",JSONObject().put("preset",c.preset.name).put("distance",c.distance).put("size",c.size).put("thickness",c.thickness).put("depth",c.depth).put("height",c.height).put("rotation",c.rotation))
        j.put("dsp",JSONObject().put("slot",s.slot).put("duration",s.duration).put("gap",s.gap).put("volume",s.volume).put("hrtf",s.hrtf).put("attenuation",s.attenuation).put("exponent",s.exponent).put("filtering",s.filtering).put("limit",s.limit).put("fade",s.fade).put("doppler",s.doppler).put("dopplerScale",s.dopplerScale).put("brightness",s.brightness).put("brightStrength",s.brightStrength).put("roughness",s.roughness).put("roughStrength",s.roughStrength).put("wave",s.wave.name))
        j.put("cells",JSONArray().also{a->cells.forEach{a.put(JSONObject().put("distance",it.distance).put("yaw",it.yaw).put("pitch",it.pitch).put("rough",it.rough).put("velocity",it.velocity).put("enabled",it.enabled).put("valid",it.valid))}})
        return j.toString()
    }
    private fun JSONObject.f(k:String,min:Float,max:Float):Float=getDouble(k).toFloat().also{require(it.isFinite()&&it in min..max){"无效参数: $k"}}
    fun decode(raw:String):SavedScene{
        val j=JSONObject(raw);require(j.getInt("version")==4);val q=j.getJSONObject("grid");val rows=q.getInt("rows");val cols=q.getInt("cols");require(rows in 1..16&&cols in 1..16)
        val g=GridConfig(rows,cols,q.f("hf",10f,120f),q.f("vf",10f,120f),q.f("az",-180f,180f),q.f("el",-90f,90f),ScanPath.valueOf(q.getString("path")))
        val c=j.getJSONObject("scene");val sc=SceneConfig(Preset.valueOf(c.getString("preset")),c.f("distance",.5f,8f),c.f("size",.3f,5f),c.f("thickness",.05f,1f),c.f("depth",.1f,2f),c.f("height",.5f,2f),c.f("rotation",-180f,180f))
        val a=j.getJSONObject("dsp");val slot=a.f("slot",10f,200f);val limit=a.f("limit",.5f,10f)
        val s=ScanSettings(slot,a.f("duration",1f,slot),a.f("gap",0f,2000f),a.f("volume",0f,100f),a.getBoolean("hrtf"),a.getBoolean("attenuation"),a.f("exponent",.2f,2f),a.getBoolean("filtering"),limit,a.f("fade",0f,limit),a.getBoolean("doppler"),a.f("dopplerScale",1f,20f),a.getBoolean("brightness"),a.f("brightStrength",0f,1f),a.getBoolean("roughness"),a.f("roughStrength",0f,1f),Wave.valueOf(a.getString("wave")))
        val cs=j.getJSONArray("cells");require(cs.length()==rows*cols)
        val cells=List(cs.length()){i->val x=cs.getJSONObject(i);Cell(x.f("distance",.05f,30f),x.f("yaw",-90f,90f),x.f("pitch",-90f,90f),x.f("rough",0f,50f),x.f("velocity",-5f,5f),x.getBoolean("enabled"),x.getBoolean("valid"))}
        return SavedScene(g,sc,s,cells)
    }
}
