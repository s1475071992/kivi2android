package com.echoguide.audiolab

import android.os.Bundle
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private val audio by lazy { SpatialAudioEngine() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { LabScreen(audio, this) } } }
    }
    override fun onDestroy() {
        audio.stop()
        super.onDestroy()
    }
}

@Composable
private fun LabScreen(audio: SpatialAudioEngine, activity: ComponentActivity) {
    var shape by remember { mutableStateOf(GeometryShape.HORIZONTAL_LINE) }
    var count by remember { mutableFloatStateOf(1f) }
    var distance by remember { mutableFloatStateOf(2f) }
    var size by remember { mutableFloatStateOf(1.2f) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var elevation by remember { mutableFloatStateOf(0f) }
    var pitDepth by remember { mutableFloatStateOf(0.4f) }
    var volume by remember { mutableFloatStateOf(20f) }
    var playing by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(SonarSettings()) }
    var status by remember { mutableStateOf(0L) }
    var blind by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    var correct by remember { mutableStateOf(0) }
    var trials by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf("") }

    val context = LocalContext.current
    var hrtfPath by remember { mutableStateOf<String?>(null) }
    var hrtfLabel by remember { mutableStateOf("Steam Audio Default") }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audio.stop()
            playing = false
            try {
                val file = File.createTempFile("hrtf-", ".sofa", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取文件")
                hrtfPath = file.absolutePath
                hrtfLabel = "自定义 SOFA"
                error = ""
            } catch (e: Exception) { error = e.message ?: "无法读取 SOFA" }
        }
    }
    val config = remember(shape, count, distance, size, azimuth, elevation, pitDepth) {
        GeometryConfig(
            shape = shape,
            sourceCount = count.roundToInt(),
            distance = distance,
            size = size,
            azimuth = azimuth,
            elevation = elevation,
            pitDepth = pitDepth
        )
    }
    val points = remember(config) { GeometryGenerator.generate(config) }
    val sonarPoints = remember(config, settings) { SonarModel.points(config, settings) }
    val scene = remember(sonarPoints, volume, settings, playing) { AudioScene(sonarPoints, volume / 100f, settings, playing) }
    LaunchedEffect(scene) { audio.update(scene) }
    LaunchedEffect(audio) { while (true) { status = audio.pingStatus; if (playing && !audio.isRunning()) playing = false; delay(100) } }
    DisposableEffect(audio, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) { audio.stop(); playing = false; status = 0L }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); audio.stop() }
    }
    fun requestPing(auto: Boolean) {
        try {
            val current = scene.copy(automatic=auto)
            audio.start(current, hrtfPath)
            audio.update(current)
            playing = auto
            if(!auto) audio.ping()
            error = ""
        } catch(e: Throwable) { playing=false; error=e.message ?: "音频初始化失败" }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("EchoGuide 3D Audio Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("v0.3 · 自研 Sonar DSP + Steam Audio 4.8.1", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { requestPing(playing); if(playing && audio.isRunning()) audio.ping() }, modifier=Modifier.weight(1f)) { Text("PING 一次") }
            Button(onClick = { requestPing(true) }, enabled=!playing, modifier=Modifier.weight(1f)) { Text("自动 PING") }
            OutlinedButton(onClick = { audio.stop(); playing=false; status=0L }, modifier=Modifier.weight(1f)) { Text("停止") }
        }
        Text("已发射 ${status ushr 32} 次 · ${if(playing) "自动" else "手动待命"}", style=MaterialTheme.typography.bodyMedium)
        Text("请求刷新 ${String.format("%.1f",settings.rate)} Hz；有效上限 ${String.format("%.2f",SonarModel.effectiveRate(sonarPoints,settings))} Hz（防止回波重叠）",style=MaterialTheme.typography.bodySmall)
        Text("HRTF: " + hrtfLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { audio.stop(); playing = false; hrtfPath = null; hrtfLabel = "Steam Audio Default"; error = "" }) { Text("默认 HRTF") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入 SOFA") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("隐藏形状盲测", blind) {
                    audio.stop(); playing=false; blind=it; answered=false; feedback=""
                    if(it) { shape=GeometryShape.values().random(); count=8f }
                }
                if(blind) {
                    Text("答对 $correct / $trials · 先 PING，再选答案")
                    OutlinedButton(onClick={ audio.stop(); playing=false; shape=GeometryShape.values().random(); answered=false; feedback="" }) { Text("下一题（随机形状）") }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        GeometryShape.values().forEach { guess ->
                            OutlinedButton(enabled=!answered,onClick={
                                trials++; if(guess==shape)correct++
                                feedback=if(guess==shape) "正确" else "正确答案："+shape.label
                                answered=true
                            }) { Text(guess.label) }
                        }
                    }
                    if(feedback.isNotEmpty())Text(feedback)
                }
            }
        }
        if(!blind) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("几何形状", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeometryShape.values().forEach { item ->
                        FilterChip(selected = shape == item, onClick = { shape = item }, label = { Text(item.label) })
                    }
                }
            }
        }

        if(!blind) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("3D 声源分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(points.size.toString() + " 个点", color = MaterialTheme.colorScheme.primary)
                }
                SourcePreview(points, shape, status.toInt())
                Text("X：左右 · Y：上下 · Z：前方。绿色为最近一次 PING 选中的点，其余为静默候选点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("参数调整", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AdjustSlider("几何总点数", count, 1f..32f, count.roundToInt().toString() + " 个", 30) { count = it.roundToInt().toFloat() }
                AdjustSlider("中心距离", distance, 0.5f..5f, String.format("%.1f", distance) + " m", 44) { distance = it }
                AdjustSlider("形状尺寸", size, 0.2f..3f, String.format("%.1f", size) + " m", 27) { size = it }
                AdjustSlider("左右方位角", azimuth, -180f..180f, azimuth.roundToInt().toString() + "°", 71) { azimuth = it }
                AdjustSlider("上下仰角", elevation, -90f..90f, elevation.roundToInt().toString() + "°", 35) { elevation = it }
                if (!blind && shape == GeometryShape.PIT) {
                    AdjustSlider("凹陷深度", pitDepth, 0.1f..1.2f, String.format("%.2f", pitDepth) + " m", 21) { pitDepth = it }
                }
                AdjustSlider("总音量", volume, 0f..100f, volume.roundToInt().toString() + "%", 19) { volume = it }
                AdjustSlider("每次 PING 发声点数", settings.simultaneous.toFloat(), 1f..32f, "${minOf(settings.simultaneous,points.size)} / ${points.size}", 30) { settings=settings.copy(simultaneous=it.roundToInt()) }
                AdjustSlider("主动刷新率", settings.rate, .5f..12f, String.format("%.1f Hz",settings.rate), 22) { settings=settings.copy(rate=it) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    PointOrder.values().forEach { order ->
                        FilterChip(selected=settings.order==order,onClick={settings=settings.copy(order=order)},label={Text(order.label)})
                    }
                }
            }
        }

        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("声音特征 / 独立对照",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Toggle("HRTF 三维方向",settings.hrtf){settings=settings.copy(hrtf=it)}
                Toggle("距离音量衰减（1/r）",settings.attenuation){settings=settings.copy(attenuation=it)}
                Toggle("发射参考声",settings.reference){settings=settings.copy(reference=it)}
                Toggle("声呐回波延迟",settings.echoDelay){settings=settings.copy(echoDelay=it)}
                AdjustSlider("延迟放大",settings.delayScale,1f..10f,String.format("%.1f×",settings.delayScale),17){settings=settings.copy(delayScale=it)}
                Toggle("多普勒频移",settings.doppler){settings=settings.copy(doppler=it)}
                AdjustSlider("模拟径向速度（正值接近）",settings.velocity,-5f..5f,String.format("%.1f m/s",settings.velocity),99){settings=settings.copy(velocity=it)}
                AdjustSlider("多普勒放大",settings.dopplerScale,1f..20f,String.format("%.0f×",settings.dopplerScale),18){settings=settings.copy(dopplerScale=it)}
                Text("基准 600 Hz；当前 ${String.format("%.1f",600f*SonarModel.dopplerRatio(settings))} Hz。采样跳转不产生频移。",style=MaterialTheme.typography.bodySmall)
                Toggle("表面朝向 → 音色明暗",settings.brightness){settings=settings.copy(brightness=it)}
                AdjustSlider("附加表面倾角",settings.tilt,-90f..90f,String.format("%.0f°",settings.tilt),35){settings=settings.copy(tilt=it)}
                AdjustSlider("明暗强度",settings.brightnessStrength,0f..1f,String.format("%.0f%%",settings.brightnessStrength*100f),19){settings=settings.copy(brightnessStrength=it)}
                Toggle("几何起伏 → 声音粗糙度",settings.roughness){settings=settings.copy(roughness=it)}
                AdjustSlider("附加局部起伏 RMS",settings.roughnessMm,0f..30f,String.format("%.1f mm",settings.roughnessMm),59){settings=settings.copy(roughnessMm=it)}
                AdjustSlider("粗糙度强度",settings.roughnessStrength,0f..1f,String.format("%.0f%%",settings.roughnessStrength*100f),19){settings=settings.copy(roughnessStrength=it)}
                Text("固定发射 8 ms / 回波 50 ms。明暗、粗糙度为物理启发的人工映射；不推断材质。",style=MaterialTheme.typography.bodySmall)
                if(!blind) sonarPoints.firstOrNull()?.let { p ->
                    Text("首点：${String.format("%.2f",p.distance)} m · 回波 ${String.format("%.1f",SonarModel.delayMs(p.distance,settings))} ms · 入射余弦 ${String.format("%.2f",p.incidence)} · RMS ${String.format("%.1f",p.rmsMm)} mm",style=MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={settings=SonarSettings()}){Text("重置 DSP")}
                    OutlinedButton(onClick={settings=settings.copy(attenuation=false,echoDelay=false,reference=false,doppler=false,brightness=false,roughness=false,hrtf=true)}){Text("仅 HRTF")}
                }
            }
        }
        Text("仅供坐姿、安静环境下的听觉实验；不可用于行走导航，也不能替代白手杖或导盲犬。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AdjustSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    steps: Int,
    change: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = change, valueRange = range, steps = steps, modifier=Modifier.semantics { contentDescription=title })
    }
}

@Composable
private fun SourcePreview(points: List<Vec3>, shape: GeometryShape, selected: Int) {
    Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF091421), RoundedCornerShape(12.dp))) {
        val cx = size.width / 2f
        val cy = size.height * 0.55f
        val radius = points.maxOfOrNull { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }?.coerceAtLeast(1f) ?: 1f
        val scale = minOf(size.width, size.height) * 0.36f / radius
        val projected = points.map { point ->
            Offset(cx + (point.x + point.z * 0.38f) * scale, cy - (point.y + point.z * 0.4f) * scale)
        }
        drawLine(Color(0xFF466078), Offset(cx, 10f), Offset(cx, size.height - 10f), 1f)
        drawLine(Color(0xFF466078), Offset(10f, cy), Offset(size.width - 10f, cy), 1f)

        if (projected.size > 1) {
            val path = Path()
            when (shape) {
                GeometryShape.HORIZONTAL_LINE, GeometryShape.VERTICAL_LINE -> {
                    path.moveTo(projected.first().x, projected.first().y)
                    projected.drop(1).forEach { path.lineTo(it.x, it.y) }
                }
                GeometryShape.RING -> {
                    val order = points.indices.sortedBy { atan2(points[it].y, points[it].x) }
                    path.moveTo(projected[order.first()].x, projected[order.first()].y)
                    order.drop(1).forEach { path.lineTo(projected[it].x, projected[it].y) }
                    path.close()
                }
                GeometryShape.RECTANGLE, GeometryShape.PIT -> {
                    val left = projected.minOf { it.x }
                    val right = projected.maxOf { it.x }
                    val top = projected.minOf { it.y }
                    val bottom = projected.maxOf { it.y }
                    path.moveTo(left, top)
                    path.lineTo(right, top)
                    path.lineTo(right, bottom)
                    path.lineTo(left, bottom)
                    path.close()
                }
            }
            drawPath(path, Color(0xFF2C879B), style = Stroke(width = 2f))
        }
        projected.forEachIndexed { index, point ->
            val active=(selected and (1 shl index)) != 0
            drawCircle(if(active) Color(0xFF45E6A0) else Color(0xFF476478), 5.5f, point)
            if(active) drawCircle(Color(0xAAE4FCFF), 8f, point, style = Stroke(width = 1f))
        }
        drawCircle(Color(0xFFFFC857), 7f, Offset(cx, cy))
    }
}
@Composable
private fun Toggle(title:String, value:Boolean, change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(title,modifier=Modifier.weight(1f))
        Switch(checked=value,onCheckedChange=change,modifier=Modifier.semantics { contentDescription=title })
    }
}
