package com.echoguide.audiolab

import kotlin.math.*

enum class PointOrder(val label: String) { SEQUENTIAL("按点序"), RANDOM("随机"), FIXED("固定首点") }

data class SonarSettings(
    val rate: Float = 3f,
    val simultaneous: Int = 1,
    val order: PointOrder = PointOrder.SEQUENTIAL,
    val hrtf: Boolean = true,
    val attenuation: Boolean = true,
    val echoDelay: Boolean = true,
    val delayScale: Float = 3f,
    val reference: Boolean = true,
    val doppler: Boolean = true,
    val velocity: Float = 0f,
    val dopplerScale: Float = 5f,
    val brightness: Boolean = true,
    val brightnessStrength: Float = 1f,
    val tilt: Float = 0f,
    val roughness: Boolean = true,
    val roughnessStrength: Float = 1f,
    val roughnessMm: Float = 0f
)
data class SonarPoint(val position: Vec3, val incidence: Float, val rmsMm: Float) {
    val distance: Float get() = sqrt(position.x*position.x+position.y*position.y+position.z*position.z)
}

object SonarModel {
    private fun radians(x: Float) = (x * PI / 180.0).toFloat()
    private fun rotate(p: Vec3, az: Float, el: Float): Vec3 {
        val a=radians(az); val e=radians(el)
        val y=p.y*cos(e)+p.z*sin(e); val z=p.z*cos(e)-p.y*sin(e)
        return Vec3(p.x*cos(a)+z*sin(a),y,z*cos(a)-p.x*sin(a))
    }
    // Lines and rings have no intrinsic surface normal: use virtual facing patches.
    // PIT uses the analytic normal of the cone-shaped bowl in GeometryGenerator.
    fun points(config: GeometryConfig, settings: SonarSettings): List<SonarPoint> {
        val local=GeometryGenerator.generate(config.copy(azimuth=0f,elevation=0f))
        val global=GeometryGenerator.generate(config)
        val radius=config.size.coerceAtLeast(.2f)/2f
        val tilt=radians(settings.tilt)
        return global.mapIndexed { i,p ->
            val q=local[i]
            val dx=q.x; val dz=q.z-config.distance-radius
            val r=sqrt(dx*dx+dz*dz)
            val slope=config.pitDepth/radius
            val pit=config.shape==GeometryShape.PIT && local.size>1
            val n=if (pit && r>1e-4f) Vec3(-slope*dx/r,1f,-slope*dz/r)
                else if(pit) Vec3(0f,1f,0f) else Vec3(0f,0f,-1f)
            val tilted=Vec3(n.x,n.y*cos(tilt)-n.z*sin(tilt),n.y*sin(tilt)+n.z*cos(tilt))
            val normal=rotate(tilted,config.azimuth,config.elevation)
            val d=sqrt(p.x*p.x+p.y*p.y+p.z*p.z).coerceAtLeast(.001f)
            val nl=sqrt(normal.x*normal.x+normal.y*normal.y+normal.z*normal.z)
            val incidence=(-(normal.x*p.x+normal.y*p.y+normal.z*p.z)/(d*nl)).coerceIn(0f,1f)
            // Approximate local residual after removing the tangent plane, over a 6 cm patch.
            val residual=if(pit) (1000f*slope*.06f*.06f/(2f*r.coerceAtLeast(.06f)*sqrt(12f))).coerceAtMost(40f) else 0f
            SonarPoint(p,incidence,sqrt(settings.roughnessMm*settings.roughnessMm+residual*residual))
        }
    }
    fun delayMs(distance: Float, s: SonarSettings) =
        if(s.echoDelay) 2000f*distance/343f*s.delayScale else 0f
    fun dopplerRatio(s: SonarSettings): Float {
        val v=if(s.doppler) (s.velocity*s.dopplerScale).coerceIn(-100f,100f) else 0f
        return (343f+v)/(343f-v)
    }
    // Native scheduler rounds upward to a 512-frame block; fixed 50ms echo + 70ms guard.
    fun effectiveRate(points: List<SonarPoint>, s: SonarSettings): Float {
        val maxDelay=points.maxOfOrNull { delayMs(it.distance,s) } ?: 0f
        val period=max(1000f/s.rate,maxDelay+120f)
        return 48000f/(ceil(period*48f/512f)*512f)
    }
    fun controls(s: SonarSettings, auto: Boolean)=floatArrayOf(
        s.rate,s.simultaneous.toFloat(),s.order.ordinal.toFloat(),if(s.hrtf)1f else 0f,
        if(s.attenuation)1f else 0f,if(s.echoDelay) s.delayScale else 0f,
        if(s.reference)1f else 0f,if(s.doppler) s.velocity*s.dopplerScale else 0f,
        if(s.brightness) s.brightnessStrength else 0f,
        if(s.roughness) s.roughnessStrength else 0f,if(auto)1f else 0f
    )
}
