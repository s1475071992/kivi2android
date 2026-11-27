package com.echoguide.audiolab

import android.os.Bundle
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
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { LabScreen(audio) } }
    }
    override fun onDestroy() {
        audio.stop()
        super.onDestroy()
    }
}

@Composable
private fun LabScreen(audio: SpatialAudioEngine) {
    var shape by remember { mutableStateOf(GeometryShape.HORIZONTAL_LINE) }
    var count by remember { mutableFloatStateOf(1f) }
    var distance by remember { mutableFloatStateOf(2f) }
    var size by remember { mutableFloatStateOf(1.2f) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var elevation by remember { mutableFloatStateOf(0f) }
    var pitDepth by remember { mutableFloatStateOf(0.4f) }
    var volume by remember { mutableFloatStateOf(20f) }
    var playing by remember { mutableStateOf(false) }

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
    val scene = remember(points, volume) { AudioScene(points, volume / 100f) }
    LaunchedEffect(scene, playing) { if (playing) audio.update(scene) }
    DisposableEffect(audio) { onDispose { audio.stop() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("EchoGuide 3D Audio Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Steam Audio 4.8.1 · HRTF 实验 v0.2", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("HRTF: " + hrtfLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { audio.stop(); playing = false; hrtfPath = null; hrtfLabel = "Steam Audio Default"; error = "" }) { Text("默认 HRTF") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入 SOFA") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("几何形状", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeometryShape.values().forEach { item ->
                        FilterChip(selected = shape == item, onClick = { shape = item }, label = { Text(item.label) })
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("3D 声源分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(points.size.toString() + " 个点", color = MaterialTheme.colorScheme.primary)
                }
                SourcePreview(points, shape)
                Text("X：左右 · Y：上下 · Z：前方。每个圆点代表一个同时播放的虚拟声源。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("参数调整", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AdjustSlider("声源数量", count, 1f..32f, count.roundToInt().toString() + " 个", 30) { count = it.roundToInt().toFloat() }
                AdjustSlider("中心距离", distance, 0.5f..5f, String.format("%.1f", distance) + " m", 44) { distance = it }
                AdjustSlider("形状尺寸", size, 0.2f..3f, String.format("%.1f", size) + " m", 27) { size = it }
                AdjustSlider("左右方位角", azimuth, -180f..180f, azimuth.roundToInt().toString() + "°", 71) { azimuth = it }
                AdjustSlider("上下仰角", elevation, -90f..90f, elevation.roundToInt().toString() + "°", 35) { elevation = it }
                if (shape == GeometryShape.PIT) {
                    AdjustSlider("凹陷深度", pitDepth, 0.1f..1.2f, String.format("%.2f", pitDepth) + " m", 21) { pitDepth = it }
                }
                AdjustSlider("总音量", volume, 0f..100f, volume.roundToInt().toString() + "%", 19) { volume = it }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { try { audio.start(scene, hrtfPath); playing = true; error = "" } catch (e: Throwable) { playing = false; error = e.message ?: "HRTF 初始化失败" } }, modifier = Modifier.weight(1f)) {
                Text(if (playing) "更新声场" else "播放声场")
            }
            OutlinedButton(onClick = { audio.stop(); playing = false }, modifier = Modifier.weight(1f)) {
                Text("停止")
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (playing) "正在同时播放 " + points.size + " 个声源" else "当前已停止", fontWeight = FontWeight.SemiBold)
                Text(
                    "每个声源独立 Steam Audio HRTF，双线性插值、100% 空间化，无混响。距离感与个体定位仍需测试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Slider(value = value, onValueChange = change, valueRange = range, steps = steps)
    }
}

@Composable
private fun SourcePreview(points: List<Vec3>, shape: GeometryShape) {
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
        projected.forEach { point ->
            drawCircle(Color(0xFF31C6D4), 5.5f, point)
            drawCircle(Color(0xAAE4FCFF), 8f, point, style = Stroke(width = 1f))
        }
        drawCircle(Color(0xFFFFC857), 7f, Offset(cx, cy))
    }
}