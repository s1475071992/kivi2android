package com.echoguide.audiolab
import org.junit.Assert.*
import org.junit.Test
class SonarModelTest {
    @Test fun frontPlaneAndTilt(){
        val config=GeometryConfig(sourceCount=1)
        val front=SonarModel.points(config,SonarSettings()).single()
        val edge=SonarModel.points(config,SonarSettings(tilt=90f)).single()
        assertEquals(1f,front.incidence,.001f)
        assertEquals(0f,edge.incidence,.001f)
        assertEquals(2f,front.distance,.001f)
    }
    @Test fun physicalMappings(){
        assertEquals(2000f/343f,SonarModel.delayMs(1f,SonarSettings(delayScale=1f)),.001f)
        assertEquals(0f,SonarModel.delayMs(3f,SonarSettings(echoDelay=false)),0f)
        assertTrue(SonarModel.dopplerRatio(SonarSettings(velocity=1f))>1f)
        assertTrue(SonarModel.dopplerRatio(SonarSettings(velocity=-1f))<1f)
        assertEquals(1f,SonarModel.dopplerRatio(SonarSettings(doppler=false,velocity=5f)),0f)
        val p=SonarModel.points(GeometryConfig(sourceCount=1,distance=5f),SonarSettings())
        assertTrue(SonarModel.effectiveRate(p,SonarSettings(rate=12f,delayScale=10f))<3f)
    }
    @Test fun roughnessAndRotation(){
        val cfg=GeometryConfig(sourceCount=1,azimuth=90f,elevation=45f)
        val p=SonarModel.points(cfg,SonarSettings(roughnessMm=10f)).single()
        assertEquals(1f,p.incidence,.001f)
        assertEquals(10f,p.rmsMm,.001f)
    }
}
