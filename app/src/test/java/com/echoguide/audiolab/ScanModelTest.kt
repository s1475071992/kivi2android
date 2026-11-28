package com.echoguide.audiolab
import org.junit.Test
import org.junit.Assert.*
class ScanModelTest{
 @Test fun paths(){val g=GridConfig();assertEquals(listOf(0,8,16,24,32,40,1),ScanModel.order(g).take(7));for(p in ScanPath.values())assertEquals(48,ScanModel.order(g.copy(path=p)).toSet().size)}
 @Test fun filter(){val s=ScanSettings();assertEquals(0f,ScanModel.gain(Cell(distance=6f),s),0f);assertEquals(0f,ScanModel.gain(Cell(valid=false),s),0f);assertTrue(ScanModel.gain(Cell(distance=1f),s)>ScanModel.gain(Cell(distance=2f),s));assertEquals(1f,ScanModel.gain(Cell(distance=6f),s.copy(filtering=false,attenuation=false)),0f)}
 @Test fun grid(){val g=GridConfig();val c=ScanModel.generate(g,SceneConfig());assertEquals(48,c.size);assertTrue(c.any{it.valid});assertTrue(c.any{!it.valid});assertEquals(384,ScanModel.packet(g,c,ScanSettings()).size);assertEquals(8,ScanModel.packet(g,c,ScanSettings(),0).size)}
}
