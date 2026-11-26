package com.blue.sherpa.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.max

/**
 * 把 sherpa-onnx 生成的 FloatArray PCM 通过 AudioTrack 播放。
 *
 * 用 16bit PCM 而非 ENCODING_PCM_FLOAT，兼容性更好；数据在调用线程分块写入，
 * stop() 可从任意线程调用并立刻中断播放。
 */
internal class SherpaTtsPlayer(sampleRate: Int) {
    @Volatile
    private var stopped = false

    private val track: AudioTrack = run {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuffer, sampleRate / 5 * 2) // 至少 200ms
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 阻塞播放；播放结束回调 [onDone]，被 [stopAndRelease] 中断则不回调。
     */
    fun play(samples: FloatArray, onDone: (Int) -> Unit, onError: (String) -> Unit) {
        try {
            val pcm = ShortArray(samples.size)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                pcm[i] = v.toShort()
            }

            track.play()
            var offset = 0
            while (offset < pcm.size && !stopped) {
                val written = track.write(pcm, offset, minOf(4096, pcm.size - offset))
                if (written <= 0) break
                offset += written
            }

            // 等 AudioTrack 把缓冲区里的数据真正播完。
            val durationMs = pcm.size * 1000L / track.sampleRate
            val deadline = SystemClock.elapsedRealtime() + durationMs + 2000L
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                if (track.playbackHeadPosition >= pcm.size) break
                Thread.sleep(20)
            }
            if (!stopped) onDone(pcm.size)
        } catch (t: Throwable) {
            if (!stopped) onError(t.message ?: "音频播放失败")
        } finally {
            stopAndRelease()
        }
    }

    /** 线程安全：重复调用不会重复 release。 */
    fun stopAndRelease() {
        stopped = true
        try {
            track.stop()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }
}
