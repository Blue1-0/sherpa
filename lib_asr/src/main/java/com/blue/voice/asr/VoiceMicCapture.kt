package com.blue.voice.asr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * 16kHz / 单声道 / PCM16 麦克风采集，按 100ms 分片转成 FloatArray 交给识别器。
 *
 * 构造时传入 Application（仅用于权限检查，避免静态引用链持有 Activity 造成泄漏），
 * [start] 内会显式检查 RECORD_AUDIO；未授权时抛 SecurityException，
 * 调用方应捕获并通过错误回调告知用户。start()/stop() 成对调用，
 * stop() 会在采集线程中等待读取循环退出，不要在采集线程内调用它。
 */
class VoiceMicCapture(
    private val context: Application,
    private val sampleRate: Int = VoiceStreamRecognizer.SAMPLE_RATE,
    private val onSamples: (FloatArray) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        // 显式检查运行时权限，避免 lint 报 MissingPermission；未授权直接抛错由上层回调。
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("缺少 RECORD_AUDIO 权限，请先申请运行时权限")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "设备不支持 ${sampleRate}Hz 单声道 PCM16 录音" }

        val record = createAudioRecord(minBuffer)
        require(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        audioRecord = record
        record.startRecording()
        running = true

        worker = Thread({
            val frameSize = sampleRate / 10 // 100ms
            val shortBuffer = ShortArray(frameSize)
            val floatBuffer = FloatArray(frameSize)
            try {
                while (running) {
                    val read = record.read(shortBuffer, 0, frameSize)
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        floatBuffer[i] = shortBuffer[i] / 32768f
                    }
                    onSamples(if (read == frameSize) floatBuffer.copyOf() else floatBuffer.copyOf(read))
                }
            } finally {
                running = false
            }
        }, "sherpa-asr-mic").apply { start() }
    }

    /** 权限已在 [start] 中显式检查，这里抑制 lint 的 MissingPermission 提示。 */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(minBuffer: Int): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        max(minBuffer, sampleRate / 5 * 2),
    )

    fun stop() {
        if (!running) return
        running = false
        try {
            worker?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        audioRecord?.run {
            try {
                stop()
            } finally {
                release()
            }
        }
        audioRecord = null
    }
}
