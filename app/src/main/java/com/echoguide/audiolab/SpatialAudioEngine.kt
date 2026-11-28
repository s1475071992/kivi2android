package com.echoguide.audiolab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
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
    @Volatile var outputError: String = ""
        private set
    @Volatile var underruns: Int = 0
        private set
    @Volatile var submittedFrames: Long = 0L
        private set
    @Volatile var playedFrames: Long = 0L
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
        outputError = ""; underruns = 0; submittedFrames = 0L; playedFrames = 0L
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
            track = output
            running = true
            worker = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val pcm = ShortArray(1024)
                    if (!newScene.automatic) {
                        // A single immutable scene is rendered in full before any audio output.
                        // Keep the existing stream track unused until the static track is ready.
                        val sampleFrames = kotlin.math.round(newScene.settings.slot.coerceIn(10f,200f)*48f).toInt() * (newScene.packet.size/8)
                        val rendered = ShortArray(sampleFrames*2)
                        var copied = 0
                        var first = true
                        while (running && copied < rendered.size) {
                            pingStatus = SteamNative.render(handle,newScene.packet,newScene.packet.size/8,newScene.masterVolume,newScene.controls,first,pcm)
                            first = false
                            val n = minOf(pcm.size,rendered.size-copied)
                            pcm.copyInto(rendered,copied,0,n);copied += n
                        }
                        if (!running || rendered.isEmpty()) return@Thread
                        var pos=0
                        // Prime the streaming buffer, then play. Every rendered sample is written.
                        val initial = minOf(rendered.size,output.bufferSizeInFrames*2)
                        while (running && pos<initial) {
                            val n=output.write(rendered,pos,initial-pos,AudioTrack.WRITE_NON_BLOCKING)
                            if(n<0) error("AudioTrack 预填充失败: $n")
                            if(n==0)break
                            pos+=n;submittedFrames=pos.toLong()/2
                        }
                        if(!running)return@Thread
                        output.play()
                        while(running && pos<rendered.size) {
                            val n=output.write(rendered,pos,rendered.size-pos,AudioTrack.WRITE_BLOCKING)
                            if(n<0)error("AudioTrack 写入失败: $n")
                            if(n==0){Thread.sleep(2);continue}
                            pos+=n;submittedFrames=pos.toLong()/2
                            playedFrames=output.playbackHeadPosition.toLong() and 0xffffffffL
                            underruns=output.underrunCount
                        }
                        while(running && playedFrames<sampleFrames) {
                            playedFrames=output.playbackHeadPosition.toLong() and 0xffffffffL
                            underruns=output.underrunCount
                            Thread.sleep(10)
                        }
                        return@Thread
                    }
                    output.play()
                    while (running && !Thread.currentThread().isInterrupted) {
                        val s = scene
                        pingStatus = SteamNative.render(handle, s.packet, s.packet.size/8, s.masterVolume, s.controls, manual.getAndSet(false), pcm)
                        var offset = 0
                        while (offset < pcm.size && running) {
                            val n = output.write(pcm, offset, pcm.size-offset, AudioTrack.WRITE_BLOCKING)
                            if (n < 0) error("AudioTrack 写入失败: $n")
                            if (n == 0) { Thread.sleep(2); continue }
                            offset += n
                            submittedFrames += n/2
                            playedFrames = output.playbackHeadPosition.toLong() and 0xffffffffL
                            underruns = output.underrunCount
                        }
                        if (offset < pcm.size) break
                    }
                } catch (e: Throwable) {
                    if(running) { outputError = e.message ?: "音频线程异常"; Log.e("EchoGuide",outputError,e) }
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
