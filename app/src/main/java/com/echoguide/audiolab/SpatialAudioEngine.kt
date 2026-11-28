package com.echoguide.audiolab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean

data class AudioScene(val packet:FloatArray,val masterVolume:Float,val settings:ScanSettings=ScanSettings(),val automatic:Boolean=false) {
    val controls=floatArrayOf(settings.slot,settings.duration,if(settings.hrtf)1f else 0f,if(settings.brightness)settings.brightStrength else 0f,if(settings.roughness)settings.roughStrength else 0f,settings.wave.ordinal.toFloat(),if(automatic)1f else 0f,settings.gap)
}

class SpatialAudioEngine {
    @Volatile private var scene = AudioScene(FloatArray(0), 0f)
    @Volatile private var running = false
    private val manual = AtomicBoolean(false)
    @Volatile var pingStatus: Long = 0L
        private set
    fun ping() { manual.set(true) }
    fun isRunning() = running
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Synchronized fun start(newScene: AudioScene, sofaPath: String? = null) {
        scene = newScene
        if (running) return
        stop()
        scene = newScene
        val handle = SteamNative.create(sofaPath)
        check(handle != 0L) { "Steam Audio 无法加载 HRTF；请检查 SOFA 格式" }
        var t: AudioTrack? = null
        try {
            val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "无法初始化音频输出" }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            if (Build.VERSION.SDK_INT >= 32) attrs.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
            val output = AudioTrack.Builder().setAudioAttributes(attrs.build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(min * 2, 512 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            t = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            output.play()
            track = output
            running = true
            worker = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val pcm = ShortArray(1024)
                    while (running && !Thread.currentThread().isInterrupted) {
                        val s = scene
                        pingStatus = SteamNative.render(handle, s.packet, s.packet.size/8, s.masterVolume, s.controls, manual.getAndSet(false), pcm)
                        var offset = 0
                        while (offset < pcm.size && running) {
                            val n = output.write(pcm, offset, pcm.size-offset, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            offset += n
                        }
                        if (offset < pcm.size) break
                    }
                } finally { SteamNative.destroy(handle); running = false }
            }, "SteamAudio-render").apply { start() }
        } catch (e: Throwable) {
            running = false
            t?.release()
            SteamNative.destroy(handle)
            throw e
        }
    }
    fun update(newScene: AudioScene) { scene = newScene }
    @Synchronized fun stop() {
        running = false
        try { track?.pause(); track?.flush() } catch (_: IllegalStateException) {}
        worker?.interrupt()
        worker?.join()
        track?.release()
        track = null
        worker = null
        manual.set(false)
        pingStatus = 0L
    }
}
object SteamNative {
    init { System.loadLibrary("echoguide_hrtf") }
    external fun create(sofaPath: String?): Long
    external fun render(handle: Long, positions: FloatArray, count: Int, volume: Float, controls: FloatArray, manual: Boolean, pcm: ShortArray): Long
    external fun destroy(handle: Long)
}
