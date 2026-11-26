package com.blue.sherpa.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.ArrayDeque

/**
 * 把 sherpa-onnx 生成的 FloatArray PCM 通过 AudioTrack 播放。
 *
 * 关键：AudioTrack.write() 在 MODE_STREAM 下是阻塞的——写入 N 秒音频要等 N 秒播放完
 * 才返回。如果在合成线程里直接 write，会把整段播放时间算进「首句出声」延迟。
 * 因此这里拆成两个线程：
 *  - 合成线程调用 [write]，只做 PCM 转换并入队，毫秒级返回；
 *  - 独立播放线程从队列取数据，慢慢写入 AudioTrack（阻塞发生在播放线程）。
 */
internal class SherpaTtsPlayer(sampleRate: Int) {
    @Volatile
    private var stopped = false

    @Volatile
    private var inputDone = false

    private val queue = ArrayDeque<ShortArray>()
    private val lock = Any()

    private val track: AudioTrack = AudioTrack.Builder()
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
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ), sampleRate / 5 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.play() }

    private val playThread = Thread({
        while (!stopped) {
            val chunk: ShortArray?
            synchronized(lock) {
                chunk = if (queue.isNotEmpty()) queue.removeFirst() else null
            }
            if (chunk != null) {
                var offset = 0
                while (offset < chunk.size && !stopped) {
                    val written = try {
                        track.write(chunk, offset, minOf(4096, chunk.size - offset))
                    } catch (_: Throwable) {
                        -1
                    }
                    if (written <= 0) break
                    offset += written
                }
            } else if (inputDone) {
                break
            } else {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }, "sherpa-tts-play").apply { isDaemon = true; start() }

    /** 兼容旧调用；AudioTrack 在构造时已启动。 */
    fun open() = Unit

    /** 非阻塞入队：只做 PCM 转换，不等待播放，返回采样数。 */
    fun write(samples: FloatArray): Int {
        if (stopped || samples.isEmpty()) return 0
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        synchronized(lock) { queue.addLast(pcm) }
        return samples.size
    }

    /** 等队列里的数据全部播完，然后释放。 */
    fun finish() {
        inputDone = true
        try {
            playThread.join(120_000)
        } catch (_: InterruptedException) {
        }
        stopAndRelease()
    }

    /** 线程安全：任意线程可调用，重复调用不会重复 release。 */
    fun stopAndRelease() {
        stopped = true
        inputDone = true
        synchronized(lock) { queue.clear() }
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
