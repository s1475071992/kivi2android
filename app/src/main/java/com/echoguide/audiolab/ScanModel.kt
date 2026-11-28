package com.echoguide.audiolab

import kotlin.math.*
enum class ScanPath(val label:String){ COLUMN("逐列"), ROW("逐行"), SNAKE("蛇形") }
enum class Wave(val label:String){ HARMONIC("宽频谐波"), NOISE("宽频噪声"), CLICK("敲击脉冲"), CHIRP("调频脉冲") }
enum class Preset(val label:String){ HORIZONTAL("横杆"), VERTICAL("竖杆"), RING("圆环"), PLANE("矩形平面"), PIT("凹坑"), RAMP("斜坡"), UP("上行台阶"), DOWN("下行台阶"), DOOR("门洞"), OVERHEAD("悬空障碍"), CORRIDOR("走廊"), MULTIPLE("多个障碍"), EMPTY("空白") }
data class GridConfig(val rows:Int=6,val cols:Int=8,val hf:Float=60f,val vf:Float=45f,val az:Float=0f,val el:Float=0f,val path:ScanPath=ScanPath.COLUMN)
data class SceneConfig(val preset:Preset=Preset.HORIZONTAL,val distance:Float=2f,val size:Float=1.8f,val thickness:Float=.35f,val depth:Float=.6f,val height:Float=1.4f,val rotation:Float=0f)
data class Cell(val distance:Float=2f,val yaw:Float=0f,val pitch:Float=0f,val rough:Float=0f,val velocity:Float=0f,val enabled:Boolean=true,val valid:Boolean=true)
data class ScanSettings(val slot:Float=20f,val duration:Float=15f,val gap:Float=0f,val volume:Float=20f,val hrtf:Boolean=true,val attenuation:Boolean=true,val exponent:Float=1f,val filtering:Boolean=true,val limit:Float=5f,val fade:Float=0f,val doppler:Boolean=false,val dopplerScale:Float=5f,val brightness:Boolean=false,val brightStrength:Float=1f,val roughness:Boolean=false,val roughStrength:Float=1f,val wave:Wave=Wave.HARMONIC)
data class Ray(val x:Float,val y:Float,val z:Float)
private operator fun Ray.plus(v:Ray)=Ray(x+v.x,y+v.y,z+v.z)
private operator fun Ray.minus(v:Ray)=Ray(x-v.x,y-v.y,z-v.z)
private operator fun Ray.times(s:Float)=Ray(x*s,y*s,z*s)
private fun Ray.dot(v:Ray)=x*v.x+y*v.y+z*v.z
private fun Ray.cross(v:Ray)=Ray(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
private fun Ray.unit():Ray { val d=sqrt(dot(this)).coerceAtLeast(1e-8f);return this*(1f/d) }
private fun rad(x:Float)=(x*PI/180).toFloat()
private fun deg(x:Float)=(x*180/PI).toFloat()
object ScanModel {
    fun ray(g:GridConfig,index:Int):Ray {
        val a=rad(g.az+((index%g.cols+.5f)/g.cols-.5f)*g.hf)
        val e=rad((g.el+(.5f-(index/g.cols+.5f)/g.rows)*g.vf).coerceIn(-89.9f,89.9f))
        return Ray(sin(a)*cos(e),sin(e),cos(a)*cos(e))
    }
    fun order(g:GridConfig):List<Int> = List(g.rows*g.cols){ i ->
        when(g.path){ ScanPath.ROW->i; ScanPath.COLUMN->(i%g.rows)*g.cols+i/g.rows; ScanPath.SNAKE->{val c=i/g.rows;val r=if(c%2==0)i%g.rows else g.rows-1-i%g.rows;r*g.cols+c} }
    }
    fun gain(c:Cell,s:ScanSettings):Float {
        if(!c.valid||!c.enabled||c.distance<=0f)return 0f
        if(s.filtering&&c.distance>s.limit)return 0f
        val fade=if(s.filtering&&s.fade>0f)((s.limit-c.distance)/s.fade).coerceIn(0f,1f) else 1f
        return fade*(if(s.attenuation) (0.5f/c.distance.coerceAtLeast(.5f)).pow(s.exponent) else 1f)
    }
    fun incidence(c:Cell,r:Ray):Float {
        // Angles are relative to the ray-facing normal, so zero means facing the listener.
        return (cos(rad(c.yaw))*cos(rad(c.pitch))).coerceIn(0f,1f)
    }
    fun packet(g:GridConfig,cells:List<Cell>,s:ScanSettings,only:Int=-1):FloatArray {
        val indices=if(only in cells.indices)listOf(only) else order(g)
        return FloatArray(indices.size*8).also { out->indices.forEachIndexed{ i,index->
            val c=cells[index];val r=ray(g,index);val j=i*8
            out[j]=r.x;out[j+1]=r.y;out[j+2]=r.z;out[j+3]=incidence(c,r);out[j+4]=c.rough;out[j+5]=if(s.doppler)c.velocity*s.dopplerScale else 0f;out[j+6]=gain(c,s);out[j+7]=index.toFloat()
        }}
    }
    private data class Tri(val a:Ray,val b:Ray,val c:Ray,val rough:Float=0f)
    private fun hit(ray:Ray,t:Tri):Float? {
        val e=t.b-t.a;val f=t.c-t.a;val p=ray.cross(f);val det=e.dot(p)
        if(abs(det)<1e-6f)return null
        val q=t.a*(-1f);val u=q.dot(p)/det;if(u<0f||u>1f)return null
        val v=ray.dot(q.cross(e))/det;if(v<0f||u+v>1f)return null
        val d=f.dot(q.cross(e))/det;return if(d>.05f)d else null
    }
    fun generate(g:GridConfig,s:SceneConfig):List<Cell> {
        val tris=mutableListOf<Tri>();val d=s.distance;val w=s.size/2f;val t=s.thickness/2f;val h=s.height
        fun quad(a:Ray,b:Ray,c:Ray,e:Ray,rough:Float=0f){tris.add(Tri(a,b,c,rough));tris.add(Tri(a,c,e,rough))}
        fun box(x0:Float,x1:Float,y0:Float,y1:Float,z0:Float,z1:Float){
            val a=Ray(x0,y0,z0);val b=Ray(x1,y0,z0);val c=Ray(x1,y1,z0);val e=Ray(x0,y1,z0)
            val f=Ray(x0,y0,z1);val j=Ray(x1,y0,z1);val k=Ray(x1,y1,z1);val l=Ray(x0,y1,z1)
            quad(a,b,c,e);quad(f,j,k,l);quad(a,f,l,e);quad(b,j,k,c);quad(a,b,j,f);quad(e,c,k,l)
        }
        fun floor(z0:Float,z1:Float,y:Float){quad(Ray(-8f,y,z0),Ray(8f,y,z0),Ray(8f,y,z1),Ray(-8f,y,z1))}
        when(s.preset){
            Preset.HORIZONTAL->box(-w,w,-t,t,d,d+2*t)
            Preset.VERTICAL->box(-t,t,-w,w,d,d+2*t)
            Preset.PLANE->box(-w,w,-w*.65f,w*.65f,d,d+2*t)
            Preset.RING->{for(i in 0 until 64){val a=(2*PI*i/64).toFloat();val b=(2*PI*(i+1)/64).toFloat();val inner=(w-2*t).coerceAtLeast(.02f);quad(Ray(w*cos(a),w*sin(a),d),Ray(w*cos(b),w*sin(b),d),Ray(inner*cos(b),inner*sin(b),d),Ray(inner*cos(a),inner*sin(a),d))}}
            Preset.PIT->{
                // Square rim surrounds a triangulated circular bowl; floor outside the rim.
                val z=d+w
                floor(.05f,d,-h);floor(d+2*w,12f,-h)
                quad(Ray(-8f,-h,d),Ray(-w,-h,d),Ray(-w,-h,d+2*w),Ray(-8f,-h,d+2*w))
                quad(Ray(w,-h,d),Ray(8f,-h,d),Ray(8f,-h,d+2*w),Ray(w,-h,d+2*w))
                for(i in 0 until 64){
                    val a=(2*PI*i/64).toFloat();val b=(2*PI*(i+1)/64).toFloat()
                    fun rim(q:Float,r:Float,y:Float)=Ray(r*cos(q),y,z+r*sin(q))
                    val squareA=w/max(abs(cos(a)),abs(sin(a)));val squareB=w/max(abs(cos(b)),abs(sin(b)))
                    quad(rim(a,w,-h),rim(b,w,-h),rim(b,squareB,-h),rim(a,squareA,-h))
                    tris.add(Tri(rim(a,w,-h),rim(b,w,-h),Ray(0f,-h-s.depth,z),4f))
                }
            }
            Preset.RAMP->{floor(.05f,d,-h);quad(Ray(-w,-h,d),Ray(w,-h,d),Ray(w,-h+s.depth,d+2*w),Ray(-w,-h+s.depth,d+2*w));floor(d+2*w,12f,-h+s.depth)}
            Preset.UP,Preset.DOWN->{floor(.05f,d,-h);val sign=if(s.preset==Preset.UP)1f else -1f;for(i in 0..3){val z=d+i*w/2;val y=-h+sign*s.depth*(i+1)/4;val prev=-h+sign*s.depth*i/4;floor(z,z+w/2,y);quad(Ray(-8f,prev,z),Ray(8f,prev,z),Ray(8f,y,z),Ray(-8f,y,z))};floor(d+2*w,12f,-h+sign*s.depth)}
            Preset.DOOR->{box(-w-t,-w,-h,h*.8f,d,d+2*t);box(w,w+t,-h,h*.8f,d,d+2*t);box(-w,w,h*.8f-t,h*.8f,d,d+2*t);floor(.05f,12f,-h)}
            Preset.OVERHEAD->{box(-w,w,.3f,.3f+2*t,d,d+2*t);floor(.05f,12f,-h)}
            Preset.CORRIDOR->{box(-w-t,-w,-h,h,d,12f);box(w,w+t,-h,h,d,12f);floor(.05f,12f,-h)}
            Preset.MULTIPLE->{box(-w,-t,-w,w,d,d+2*t);box(t,w,-w*.6f,w*.6f,d+1f,d+1f+2*t)}
            Preset.EMPTY->{}
        }
        val a=rad(s.rotation);fun rotate(p:Ray):Ray {val z=p.z-d;return Ray(p.x*cos(a)+z*sin(a),p.y,d+z*cos(a)-p.x*sin(a))}
        val mesh=if(s.rotation==0f)tris else tris.map{Tri(rotate(it.a),rotate(it.b),rotate(it.c),it.rough)}
        return List(g.rows*g.cols){ i->
            val r=ray(g,i);var best=30f;var target:Tri?=null
            for(tri in mesh){val dist=hit(r,tri);if(dist!=null&&dist<best){best=dist;target=tri}}
            val tri=target
            if(tri==null)Cell(distance=10f,valid=false) else {
                val n=(tri.b-tri.a).cross(tri.c-tri.a).unit()
                val angle=deg(acos(abs(n.dot(r)).coerceIn(0f,1f)))
                Cell(distance=best,yaw=angle,rough=tri.rough)
            }
        }
    }
}
