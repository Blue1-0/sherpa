package com.blue.sherpa.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx 流式识别封装（中英双语 Zipformer transducer int8）。
 *
 * 所有 JNI 调用必须串行，同一实例不要并发调用；麦克风采集线程顺序调用
 * [start]/[accept]/[stop] 即可。回调发生在调用线程，UI 层需要自行切回主线程。
 */
class SherpaStreamRecognizer private constructor(
    private val recognizer: OnlineRecognizer,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * 从指定目录加载模型。该方法较重（加载 ONNX 权重），必须在后台线程调用。
         * 默认目录为 [SherpaAsrModels.modelDir]，也支持传入服务器下载后的任意目录。
         */
        fun create(
            modelDir: File,
            numThreads: Int = 2,
            onPartial: (String) -> Unit = {},
            onFinal: (String) -> Unit = {},
        ): SherpaStreamRecognizer {
            val missing = SherpaAsrModels.missingFiles(modelDir)
            require(missing.isEmpty()) { "模型文件缺失: ${missing.joinToString()}" }

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, SherpaAsrModels.ENCODER).absolutePath,
                        decoder = File(modelDir, SherpaAsrModels.DECODER).absolutePath,
                        joiner = File(modelDir, SherpaAsrModels.JOINER).absolutePath,
                    ),
                    tokens = File(modelDir, SherpaAsrModels.TOKENS).absolutePath,
                    numThreads = numThreads,
                    modelType = "zipformer",
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            return SherpaStreamRecognizer(
                recognizer = OnlineRecognizer(config = config),
                onPartial = onPartial,
                onFinal = onFinal,
            )
        }
    }

    private val lock = Any()
    private var stream: OnlineStream? = null
    private val committed = StringBuilder()

    val isStarted: Boolean
        get() = synchronized(lock) { stream != null }

    /** 开始一段语音；hotwords 为换行分隔的热词（可为空）。 */
    fun start(hotwords: String = "", prefixSilenceMs: Int = 100) {
        synchronized(lock) {
            releaseStream()
            committed.clear()
            stream = recognizer.createStream(hotwords).also { s ->
                if (prefixSilenceMs > 0) {
                    s.acceptWaveform(FloatArray(SAMPLE_RATE * prefixSilenceMs / 1000), SAMPLE_RATE)
                }
            }
        }
    }

    /** 送入 16kHz 单声道 PCM 浮点采样（范围 -1f..1f）。 */
    fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            val current = stream ?: return
            current.acceptWaveform(samples, SAMPLE_RATE)
            decode(current)

            when {
                recognizer.isEndpoint(current) -> {
                    val segment = recognizer.getResult(current).text.trim()
                    if (segment.isNotEmpty()) {
                        committed.append(segment)
                        onFinal(committed.toString())
                    }
                    recognizer.reset(current)
                }

                else -> {
                    val partial = recognizer.getResult(current).text.trim()
                    if (partial.isNotEmpty()) {
                        onPartial((committed.toString() + partial).trim())
                    }
                }
            }
        }
    }

    /** 结束本段语音，补尾部静音后取最终结果。 */
    fun stop(): String {
        synchronized(lock) {
            val current = stream ?: return committed.toString().trim().also {
                if (it.isNotEmpty()) onFinal(it)
            }
            current.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            current.inputFinished()
            decode(current)

            val tail = recognizer.getResult(current).text.trim()
            if (tail.isNotEmpty()) committed.append(tail)
            releaseStream()

            val result = committed.toString().trim()
            onFinal(result)
            return result
        }
    }

    /** 丢弃当前语音，不产生 final 回调。 */
    fun cancel() {
        synchronized(lock) {
            releaseStream()
            committed.clear()
        }
    }

    fun close() {
        synchronized(lock) {
            releaseStream()
            recognizer.release()
        }
    }

    private fun decode(current: OnlineStream) {
        var guard = 0
        while (recognizer.isReady(current) && guard++ < 200) {
            recognizer.decode(current)
        }
    }

    private fun releaseStream() {
        stream?.release()
        stream = null
    }
}
