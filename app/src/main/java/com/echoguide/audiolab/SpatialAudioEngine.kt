package com.echoguide.audiolab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioScene(val sources: List<Vec3>, val masterVolume: Float)

/**
 * POC renderer: equal-power stereo panning + approximate ITD + a weak generic
 * elevation spectral cue. It is not a measured or personalized HRTF renderer.
 */
class SpatialAudioEngine {
    @Volatile private var scene: AudioScene? = null
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Synchronized
    fun start(newScene: AudioScene) {
        scene = newScene
        if (running) return

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BLOCK_FRAMES * 4)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer * 2, BLOCK_FRAMES * 16))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        track = audioTrack
        running = true
        audioTrack.play()
        worker = Thread({ render(audioTrack) }, "EchoGuide-Audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun update(newScene: AudioScene) {
        scene = newScene
    }

    @Synchronized
    fun stop() {
        running = false
        scene = null
        val currentTrack = track
        try {
            currentTrack?.pause()
            currentTrack?.flush()
        } catch (_: IllegalStateException) {
        }
        worker?.interrupt()
        try {
            worker?.join(350)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            currentTrack?.release()
        } catch (_: IllegalStateException) {
        }
        worker = null
        track = null
    }

    private fun render(audioTrack: AudioTrack) {
        val output = ShortArray(BLOCK_FRAMES * 2)
        var frameCursor = 0L
        while (running && !Thread.currentThread().isInterrupted) {
            val current = scene
            val sources = current?.sources.orEmpty()
            val volume = (current?.masterVolume ?: 0f).coerceIn(0f, 1f)
            val normalization = if (sources.isEmpty()) 0f else 0.72f / sqrt(sources.size.toFloat())

            for (frame in 0 until BLOCK_FRAMES) {
                val absoluteFrame = frameCursor + frame
                val time = absoluteFrame.toFloat() / SAMPLE_RATE
                var left = 0f
                var right = 0f

                for (index in sources.indices) {
                    val point = sources[index]
                    val horizontal = sqrt(point.x * point.x + point.z * point.z).coerceAtLeast(0.01f)
                    val distance = sqrt(point.x * point.x + point.y * point.y + point.z * point.z)
                    val azimuth = atan2(point.x, point.z)
                    val elevation = atan2(point.y, horizontal)
                    val pan = sin(azimuth).coerceIn(-1f, 1f)

                    val itdFrames = (0.00063f * sin(azimuth) * SAMPLE_RATE).toInt()
                    val leftDelay = if (itdFrames < 0) -itdFrames else 0
                    val rightDelay = if (itdFrames > 0) itdFrames else 0
                    val leftSample = sourceTexture(absoluteFrame - leftDelay, index, time, elevation)
                    val rightSample = sourceTexture(absoluteFrame - rightDelay, index, time, elevation)

                    val leftPan = sqrt(((1f - pan) * 0.5f).coerceAtLeast(0f))
                    val rightPan = sqrt(((1f + pan) * 0.5f).coerceAtLeast(0f))
                    val farEar = 1f - 0.18f * abs(pan)
                    val leftGain = if (pan > 0f) leftPan * farEar else leftPan
                    val rightGain = if (pan < 0f) rightPan * farEar else rightPan
                    val gain = volume * normalization / (1f + 0.32f * distance)

                    left += leftSample * leftGain * gain
                    right += rightSample * rightGain * gain
                }

                output[frame * 2] = (left.coerceIn(-0.95f, 0.95f) * Short.MAX_VALUE).toInt().toShort()
                output[frame * 2 + 1] = (right.coerceIn(-0.95f, 0.95f) * Short.MAX_VALUE).toInt().toShort()
            }

            val written = audioTrack.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            frameCursor += BLOCK_FRAMES
        }
    }

    // Same broadband texture for every source; each source has an independent noise stream.
    private fun sourceTexture(frame: Long, sourceIndex: Int, time: Float, elevation: Float): Float {
        val n0 = noise(frame, sourceIndex)
        val n1 = noise(frame - 1, sourceIndex)
        val n2 = noise(frame - 2, sourceIndex)
        val softNoise = (n0 + n1 + n2) / 3f
        val highDetail = n0 - n2
        val verticalCue = elevation.coerceIn(-0.65f, 0.65f)
        val shaped = softNoise * (1f - abs(verticalCue) * 0.12f) + highDetail * verticalCue * 0.08f
        val pulse = 0.58f + 0.42f * (0.5f + 0.5f * sin(2f * PI.toFloat() * 1.35f * time))
        return shaped * pulse
    }

    private fun noise(frame: Long, sourceIndex: Int): Float {
        var value = frame.toInt() xor ((sourceIndex + 1) * 0x45d9f3b)
        value = value xor (value ushr 16)
        value *= 0x45d9f3b
        value = value xor (value ushr 16)
        return ((value and 0x00ffffff) / 8_388_607.5f) - 1f
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_FRAMES = 256
    }
}