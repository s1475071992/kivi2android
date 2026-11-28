package com.echoguide.audiolab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.*
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity(){
    private val audio by lazy { SpatialAudioEngine() }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Lab(audio,this) } } }
    }
    override fun onDestroy(){audio.stop();super.onDestroy()}
}
enum class EditScope(val label:String){ SINGLE("单格"), MULTI("多选"), ALL("全局") }
enum class TrialKind(val label:String){ SHAPE("形状盲测"), DIRECTION("方向 A/B"), DISTANCE("距离 A/B"), BRIGHT("明暗 A/B"), ROUGH("粗糙度 A/B"), DOPPLER("多普勒 A/B") }
data class Trial(val kind:TrialKind,val answer:Int,val start:Long,val preset:Preset=Preset.HORIZONTAL)
private fun fmt(x:Float,d:Int=1)=String.format(java.util.Locale.US,"%.${d}f",x)

@Composable private fun Lab(audio:SpatialAudioEngine,activity:ComponentActivity){
    val context=LocalContext.current
    var g by remember{mutableStateOf(GridConfig())}
    var sceneConfig by remember{mutableStateOf(SceneConfig())}
    var s by remember{mutableStateOf(ScanSettings())}
    var cells by remember{mutableStateOf(ScanModel.generate(g,sceneConfig))}
    var selected by remember{mutableStateOf(setOf(0))}
    var anchor by remember{mutableIntStateOf(0)}
    var scope by remember{mutableStateOf(EditScope.SINGLE)}
    var auto by remember{mutableStateOf(false)}
    var status by remember{mutableLongStateOf(0L)}
    var sofa by remember{mutableStateOf<String?>(null)}
    var error by remember{mutableStateOf("")}
    var tab by remember{mutableIntStateOf(0)}
    var trialKind by remember{mutableStateOf(TrialKind.SHAPE)}
    var trial by remember{mutableStateOf<Trial?>(null)}
    var result by remember{mutableStateOf("")}
    var records by remember{mutableStateOf(listOf<String>())}
    var correct by remember{mutableIntStateOf(0)}
    var deltaAngle by remember{mutableFloatStateOf(15f)}
    var deltaDistance by remember{mutableFloatStateOf(1f)}
    var deltaRough by remember{mutableFloatStateOf(25f)}
    var deltaVelocity by remember{mutableFloatStateOf(3f)}
    var deltaTilt by remember{mutableFloatStateOf(75f)}
    val presets=Preset.values().filter{it!=Preset.EMPTY}
    val packet=remember(g,cells,s){ScanModel.packet(g,cells,s)}
    val currentScene=remember(packet,s,auto){AudioScene(packet,s.volume/100f,s,auto)}
    fun stop(){audio.stop();auto=false;status=0L}
    fun play(a:AudioScene,manual:Boolean){try{audio.start(a,sofa);audio.update(a);if(manual)audio.ping();error=""}catch(e:Throwable){auto=false;error=e.message?:"音频初始化失败"}}
    fun manual(packetOverride:FloatArray=packet,settings:ScanSettings=s){stop();play(AudioScene(packetOverride,settings.volume/100f,settings,false),true)}
    fun regenerate(ng:GridConfig=g,ns:SceneConfig=sceneConfig){stop();g=ng;sceneConfig=ns;cells=ScanModel.generate(ng,ns);anchor=0;selected=setOf(0);trial=null}
    fun edit(change:(Cell)->Cell){val indices=when(scope){EditScope.ALL->cells.indices.toSet();EditScope.MULTI->selected;EditScope.SINGLE->setOf(anchor)};cells=cells.mapIndexed{i,c->if(i in indices)change(c) else c}}
    // The snapshot updates only on normal lab screens. Trial playback is isolated.
    LaunchedEffect(currentScene,tab){if(tab!=2)audio.update(currentScene)}
    LaunchedEffect(audio){while(true){status=audio.pingStatus;delay(40)}}
    DisposableEffect(activity,audio){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){audio.stop();auto=false;status=0L}};activity.lifecycle.addObserver(observer);onDispose{activity.lifecycle.removeObserver(observer);audio.stop()}}
    val pickSofa=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){stop();try{val f=File.createTempFile("hrtf-",".sofa",context.cacheDir);context.contentResolver.openInputStream(uri)?.use{input->f.outputStream().use{input.copyTo(it)}}?:error("无法读取 SOFA");sofa=f.absolutePath;error=""}catch(e:Exception){error=e.message?:"SOFA 读取失败"}}
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)try{context.contentResolver.openOutputStream(uri)?.use{it.write(SceneCodec.encode(g,sceneConfig,s,cells).toByteArray())}?:error("无法保存");error="场景已保存"}catch(e:Exception){error=e.message?:"保存失败"}
    }
    val load=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)try{val raw=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("读取失败");val v=SceneCodec.decode(raw);stop();g=v.g;sceneConfig=v.scene;s=v.s;cells=v.cells;anchor=0;selected=setOf(0);trial=null;error="场景已加载"}catch(e:Exception){error=e.message?:"加载失败"}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null)try{context.contentResolver.openOutputStream(uri)?.use{it.write(("test,answer,choice,correct,response_ms,rows,cols,slot_ms,duration_ms,angle_deg,distance_m,tilt_deg,rough_mm,velocity_mps,settings_json\n"+records.joinToString("\n")).toByteArray())}?:error("无法保存");error="CSV 已导出"}catch(e:Exception){error=e.message?:"导出失败"}
    }
    fun trialAudio(t:Trial){
        if(t.kind==TrialKind.SHAPE){
            val tc=ScanModel.generate(g,sceneConfig.copy(preset=t.preset))
            manual(ScanModel.packet(g,tc,s),s);return
        }
        // A/B slots deliberately separated by silent slots; no spatial marker sounds.
        val ts=s.copy(slot=200f,duration=150f,gap=0f,filtering=false,hrtf=t.kind==TrialKind.DIRECTION,
            attenuation=t.kind==TrialKind.DISTANCE,brightness=t.kind==TrialKind.BRIGHT,roughness=t.kind==TrialKind.ROUGH,doppler=t.kind==TrialKind.DOPPLER,wave=Wave.HARMONIC)
        val p=FloatArray(8*5)
        for(i in 0..1){
            val different=i==t.answer
            val a=if(t.kind==TrialKind.DIRECTION&&different)deltaAngle else 0f
            val c=Cell(distance=if(t.kind==TrialKind.DISTANCE&&different)2f+deltaDistance else 2f,yaw=if(t.kind==TrialKind.BRIGHT&&different)deltaTilt else 0f,rough=if(t.kind==TrialKind.ROUGH&&different)deltaRough else 0f,velocity=if(t.kind==TrialKind.DOPPLER&&different)deltaVelocity else 0f)
            val one=ScanModel.packet(GridConfig(1,1,az=a),listOf(c),ts)
            one.copyInto(p,i*32);p[i*32+7]=i.toFloat()
        }
        // silent positions have gain zero, keep 3 slots between A and B
        manual(p,ts)
    }
    fun newTrial(){stop();val preset=presets.random();val t=Trial(trialKind,if(trialKind==TrialKind.SHAPE)presets.indexOf(preset) else (0..1).random(),android.os.SystemClock.elapsedRealtime(),preset);trial=t;result="";trialAudio(t)}
    fun answer(i:Int){val t=trial?:return;val ok=i==t.answer;val elapsed=android.os.SystemClock.elapsedRealtime()-t.start;if(ok)correct++;val config=SceneCodec.encode(g,sceneConfig,s,emptyList()).replace("\"","\\"\"");records=records+("${t.kind.name},${t.answer},$i,$ok,$elapsed,${g.rows},${g.cols},${s.slot},${s.duration},$deltaAngle,$deltaDistance,$deltaTilt,$deltaRough,$deltaVelocity,\"$config\"");result=if(ok)"正确" else "正确答案："+if(t.kind==TrialKind.SHAPE)t.preset.label else if(t.answer==0)"A" else "B";trial=null;stop()}
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)){
        Text("EchoGuide · v0.4",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("空间栅格听觉实验室 · Steam Audio HRTF",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Button(onClick={manual()},modifier=Modifier.weight(1f),enabled=tab!=2){Text("PING")}
            Button(onClick={stop();auto=true;play(currentScene.copy(automatic=true),false)},modifier=Modifier.weight(1f),enabled=tab!=2&&!auto){Text("自动")}
            OutlinedButton(onClick={stop()},modifier=Modifier.weight(1f)){Text("停止")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("栅格 / 场景","声音 / 扫描","测试中心").forEachIndexed{i,t->FilterChip(selected=tab==i,onClick={stop();trial=null;tab=i},label={Text(t)})}
        }
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            if(tab==0){
                Text("深度栅格 · ${g.rows}×${g.cols} · ${cells.count{ScanModel.gain(it,s)>0f}} 格发声",style=MaterialTheme.typography.titleMedium)
                Text("第 ${status ushr 32} 帧 · ${if(auto)"循环扫描" else "手动"} · ${fmt(g.rows*g.cols*s.slot/1000f,2)} 秒/帧")
                val active=(status and 0xffffffffL).toInt()-1
                Column(Modifier.horizontalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    repeat(g.rows){r->Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
                        repeat(g.cols){c->val i=r*g.cols+c;val cell=cells[i];val playing=i==active;val marked=i in selected;val gain=ScanModel.gain(cell,s)
                            val color=if(playing)Color(0xFFE5AA46) else if(marked)Color(0xFF527AB3) else if(gain>0)Color(0xFF237C6C) else Color(0xFF3B4249)
                            Box(Modifier.size(45.dp,42.dp).background(color,RoundedCornerShape(5.dp)).clickable{
                                anchor=i;selected=if(scope==EditScope.MULTI){if(i in selected)selected-i else selected+i}else setOf(i)
                            }.semantics{contentDescription="第${r+1}行第${c+1}列 "+if(cell.valid)fmt(cell.distance)+"米" else "无效深度"},contentAlignment=Alignment.Center){Text(if(!cell.valid)"—" else if(!cell.enabled)"×" else fmt(cell.distance),style=MaterialTheme.typography.labelSmall)}
                        }
                    }}
                }
                Text("蓝色选中 · 黄色扫描中 · 绿色可播放 · 灰色静默（米）",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){EditScope.values().forEach{m->FilterChip(selected=scope==m,onClick={scope=m;if(m==EditScope.SINGLE)selected=setOf(anchor)},label={Text(m.label)})}}
                val c=cells[anchor.coerceIn(cells.indices)]
                Text("第 ${anchor/g.cols+1} 行 · 第 ${anchor%g.cols+1} 列 | ${when(scope){EditScope.SINGLE->1;EditScope.MULTI->selected.size;EditScope.ALL->cells.size}} 格编辑")
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={manual(ScanModel.packet(g,cells,s,anchor))}){Text("单点试听")};OutlinedButton(onClick={val original=ScanModel.generate(g,sceneConfig);val ids=when(scope){EditScope.ALL->cells.indices.toSet();EditScope.SINGLE->setOf(anchor);EditScope.MULTI->selected};cells=cells.mapIndexed{i,v->if(i in ids)original[i]else v}}){Text("恢复选中")}}
                Toggle("有效深度",c.valid){v->edit{it.copy(valid=v)}};Toggle("参与播放",c.enabled){v->edit{it.copy(enabled=v)}}
                Knob("距离",c.distance.coerceIn(.2f,30f),.2f..30f,"m"){v->edit{it.copy(distance=v,valid=true)}}
                Knob("法线水平倾角",c.yaw,-90f..90f,"°"){v->edit{it.copy(yaw=v)}}
                Knob("法线垂直倾角",c.pitch,-90f..90f,"°"){v->edit{it.copy(pitch=v)}}
                Knob("局部起伏 RMS",c.rough,0f..50f,"mm"){v->edit{it.copy(rough=v)}}
                Knob("径向速度（正=接近）",c.velocity,-5f..5f,"m/s"){v->edit{it.copy(velocity=v)}}
                HorizontalDivider()
                Text("场景生成（会覆盖手动编辑）",style=MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){Preset.values().forEach{p->FilterChip(selected=sceneConfig.preset==p,onClick={regenerate(ns=sceneConfig.copy(preset=p))},label={Text(p.label)})}}
                Knob("场景距离",sceneConfig.distance,.5f..8f,"m"){regenerate(ns=sceneConfig.copy(distance=it))}
                Knob("尺寸",sceneConfig.size,.3f..5f,"m"){regenerate(ns=sceneConfig.copy(size=it))}
                Knob("厚度",sceneConfig.thickness,.05f..1f,"m"){regenerate(ns=sceneConfig.copy(thickness=it))}
                Knob("凹陷 / 高差",sceneConfig.depth,.1f..2f,"m"){regenerate(ns=sceneConfig.copy(depth=it))}
                Knob("观察者高度",sceneConfig.height,.5f..2f,"m"){regenerate(ns=sceneConfig.copy(height=it))}
                Knob("场景旋转",sceneConfig.rotation,-180f..180f,"°"){regenerate(ns=sceneConfig.copy(rotation=it))}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={save.launch("EchoGuide-scene.json")}){Text("保存 JSON")};OutlinedButton(onClick={load.launch(arrayOf("application/json","text/plain","*/*"))}){Text("加载 JSON")}}
            }
            if(tab==1){
                Text("扫描几何（修改将重新生成栅格）",style=MaterialTheme.typography.titleMedium)
                Knob("垂直行数",g.rows.toFloat(),1f..16f,"",14){regenerate(g.copy(rows=it.roundToInt()))}
                Knob("水平列数",g.cols.toFloat(),1f..16f,"",14){regenerate(g.copy(cols=it.roundToInt()))}
                Knob("水平视野",g.hf,10f..120f,"°"){regenerate(g.copy(hf=it))}
                Knob("垂直视野",g.vf,10f..120f,"°"){regenerate(g.copy(vf=it))}
                Knob("中心方位",g.az,-180f..180f,"°"){regenerate(g.copy(az=it))}
                Knob("中心仰角",g.el,-90f..90f,"°"){regenerate(g.copy(el=it))}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ScanPath.values().forEach{p->FilterChip(selected=g.path==p,onClick={g=g.copy(path=p)},label={Text(p.label)})}}
                Knob("每点时间槽",s.slot,10f..200f,"ms"){s=s.copy(slot=it,duration=min(s.duration,it))}
                Knob("固定脉冲时长",s.duration,1f..s.slot,"ms"){s=s.copy(duration=it)}
                Knob("帧间静默",s.gap,0f..2000f,"ms"){s=s.copy(gap=it)}
                Text("一帧 ${fmt(g.rows*g.cols*s.slot/1000f,2)} 秒；无回声延迟、无参考音、无同步标记。")
                HorizontalDivider();Text("距离过滤与衰减",style=MaterialTheme.typography.titleMedium)
                Toggle("最大距离过滤",s.filtering){s=s.copy(filtering=it)}
                Knob("最大播放距离",s.limit,.5f..10f,"m"){s=s.copy(limit=it,fade=min(s.fade,it))}
                Knob("截止渐变宽度（0=硬截止）",s.fade,0f..s.limit,"m"){s=s.copy(fade=it)}
                Toggle("距离衰减",s.attenuation){s=s.copy(attenuation=it)}
                Knob("距离衰减指数",s.exponent,.2f..2f,""){s=s.copy(exponent=it)}
                HorizontalDivider();Text("DSP 与 HRTF",style=MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){Wave.values().forEach{w->FilterChip(selected=s.wave==w,onClick={s=s.copy(wave=w)},label={Text(w.label)})}}
                Toggle("HRTF 三维方向",s.hrtf){s=s.copy(hrtf=it)}
                Text(if(sofa==null)"Steam Audio 默认 HRTF" else "自定义 SOFA")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={stop();sofa=null}){Text("默认 HRTF")};OutlinedButton(onClick={pickSofa.launch(arrayOf("*/*"))}){Text("导入 SOFA")}}
                Toggle("多普勒",s.doppler){s=s.copy(doppler=it)}
                Knob("多普勒放大",s.dopplerScale,1f..20f,"×"){s=s.copy(dopplerScale=it)}
                Toggle("音色明暗",s.brightness){s=s.copy(brightness=it)}
                Knob("明暗强度",s.brightStrength,0f..1f,""){s=s.copy(brightStrength=it)}
                Toggle("声音粗糙度",s.roughness){s=s.copy(roughness=it)}
                Knob("粗糙度强度",s.roughStrength,0f..1f,""){s=s.copy(roughStrength=it)}
                Knob("总音量",s.volume,0f..100f,"%"){s=s.copy(volume=it)}
                OutlinedButton(onClick={s=ScanSettings()}){Text("恢复默认 DSP")}
            }
            if(tab==2){
                Text("听觉测试中心",style=MaterialTheme.typography.titleMedium)
                Text("A/B：听两段声音，选择位置更右、距离更远、音色更暗、声音更粗糙或频率更高的一段。形状测试随机选择场景。")
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){TrialKind.values().forEach{k->FilterChip(selected=trialKind==k,onClick={stop();trial=null;result="";trialKind=k},label={Text(k.label)})}}
                when(trialKind){
                    TrialKind.DIRECTION->Knob("水平角度差",deltaAngle,2f..60f,"°"){deltaAngle=it}
                    TrialKind.DISTANCE->Knob("距离差",deltaDistance,.1f..5f,"m"){deltaDistance=it}
                    TrialKind.BRIGHT->Knob("表面倾角差",deltaTilt,5f..90f,"°"){deltaTilt=it}
                    TrialKind.ROUGH->Knob("局部起伏差",deltaRough,1f..50f,"mm"){deltaRough=it}
                    TrialKind.DOPPLER->Knob("径向速度差",deltaVelocity,.1f..5f,"m/s"){deltaVelocity=it}
                    else->Text("使用当前分辨率、视野和 DSP。可到扫描设置调整后重测。")
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick={newTrial()}){Text("随机新题")};OutlinedButton(enabled=trial!=null,onClick={trial?.let{trialAudio(it)}}){Text("重新试听")}}
                if(trial!=null){
                    if(trialKind==TrialKind.SHAPE){presets.chunked(3).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){row.forEach{p->OutlinedButton(onClick={answer(presets.indexOf(p))},modifier=Modifier.weight(1f)){Text(p.label)}}}}}
                    else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={answer(0)}){Text("A")};Button(onClick={answer(1)}){Text("B")}}
                }
                if(result.isNotEmpty())Text(result,fontWeight=FontWeight.Bold)
                Text("累计正确 $correct / ${records.size} · ${if(records.isEmpty())0 else correct*100/records.size}%")
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(enabled=records.isNotEmpty(),onClick={export.launch("EchoGuide-tests.csv")}){Text("导出 CSV")};OutlinedButton(onClick={records=emptyList();correct=0;result=""}){Text("清除记录")}}
                Text("记录从出题到回答的总时间，包含播放及重复试听；不是临床测量。测试参数和记录仅保留于本次会话，需手动导出。")
            }
            Text("仅供静坐耳机实验。无效深度和超距静默不表示可安全通行。不可用于真实导航。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun Knob(title:String,value:Float,range:ClosedFloatingPointRange<Float>,unit:String,steps:Int=0,change:(Float)->Unit){
    Row(verticalAlignment=Alignment.CenterVertically){Text(title,modifier=Modifier.weight(1f));Text(fmt(value)+" "+unit,color=MaterialTheme.colorScheme.primary)}
    Slider(value=value.coerceIn(range.start,range.endInclusive),onValueChange=change,valueRange=range,steps=steps,modifier=Modifier.semantics{contentDescription=title})
}
@Composable private fun Toggle(title:String,on:Boolean,change:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Text(title,modifier=Modifier.weight(1f));Switch(checked=on,onCheckedChange=change,modifier=Modifier.semantics{contentDescription=title})}}
