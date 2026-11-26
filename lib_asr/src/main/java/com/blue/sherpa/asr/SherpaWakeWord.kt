package com.blue.sherpa.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 中文唤醒词（KWS）封装：基于 sherpa-onnx 的 KeywordSpotter，
 * 用一个约 5MB 的小模型常驻监听，只识别词表里列出的唤醒词。
 *
 * 每次 [accept] 送入 16kHz 单声道 PCM 浮点采样（与 [SherpaMicCapture] 输出一致）；
 * 命中时通过 [onKeyword] 回调词文本，随后内部自动 reset 以便再次唤醒。
 * 所有 JNI 调用须串行，回调发生在调用线程。加载模型较重，须在后台线程 [create]。
 */
class SherpaWakeWord private constructor(
    private val spotter: KeywordSpotter,
    private val onKeyword: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * 内置默认唤醒词「小卓小卓」，已按 wenetspeech 模型的 ppinyin 建模单元 token 化。
         * 拆分为 小=x+iǎo，卓=zh+uó，逐 token 与 tokens.txt 一一对应。
         * 末尾 @ 后是命中回调返回的原文（ppinyin 模型必须提供，且不能含空格）。
         * boosting/阈值由 WakeWordConfig 的 keywordsScore/keywordsThreshold 统一配置，
         * 这里不再写 ":x #x"，避免与配置值重复且互相覆盖。
         */
        const val DEFAULT_KEYWORDS = "x iǎo zh uó x iǎo zh uó @小卓小卓"

        /**
         * 从 KWS 模型目录加载。
         *
         * 词表来源优先级：
         * 1) [keywords]：已 token 化的字符串（每行一词），会写入 [keywordsCacheDir] 下的
         *    keywords_custom.txt 并作为构造词表——推荐方式，可彻底替换模型自带词表。
         * 2) [keywordsFile]：外部已 token 化的词表文件（高级场景）。
         * 3) 都不传：使用模型目录内自带的 keywords.txt（含小爱同学/小艺小艺等）。
         *
         * 中文词无法直接传汉字，必须先用 sherpa-onnx-cli text2token 转成 ppinyin token。
         */
        fun create(
            modelDir: File,
            keywords: String? = null,
            keywordsFile: File? = null,
            keywordsCacheDir: File? = null,
            keywordsScore: Float = 2.0f,
            keywordsThreshold: Float = 0.15f,
            numTrailingBlanks: Int = 1,
            maxActivePaths: Int = 4,
            numThreads: Int = 1,
            onKeyword: (String) -> Unit,
        ): SherpaWakeWord {
            val missing = SherpaKwsModels.missingFiles(modelDir)
            require(missing.isEmpty()) { "唤醒词模型文件缺失: ${missing.joinToString()}" }

            // 词表文件：字符串 > 外部文件 > 模型自带 keywords.txt
            val effectiveKeywordsFile = when {
                !keywords.isNullOrBlank() -> {
                    val dir = keywordsCacheDir ?: File(modelDir.parentFile, "keywords_cache").also { it.mkdirs() }
                    dir.mkdirs()
                    val file = File(dir, "keywords_custom.txt")
                    file.writeText(keywords.trim() + "\n", Charsets.UTF_8)
                    file
                }
                keywordsFile != null -> keywordsFile
                else -> File(modelDir, SherpaKwsModels.KEYWORDS)
            }
            require(effectiveKeywordsFile.isFile) {
                "唤醒词文件不存在: ${effectiveKeywordsFile.absolutePath}"
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, SherpaKwsModels.ENCODER).absolutePath,
                        decoder = File(modelDir, SherpaKwsModels.DECODER).absolutePath,
                        joiner = File(modelDir, SherpaKwsModels.JOINER).absolutePath,
                    ),
                    tokens = File(modelDir, SherpaKwsModels.TOKENS).absolutePath,
                    numThreads = numThreads,
                    modelType = "zipformer2",
                ),
                keywordsFile = effectiveKeywordsFile.absolutePath,
                keywordsScore = keywordsScore,
                keywordsThreshold = keywordsThreshold,
                numTrailingBlanks = numTrailingBlanks,
                maxActivePaths = maxActivePaths,
            )
            val spotter = KeywordSpotter(config = config)
            return SherpaWakeWord(spotter, onKeyword)
        }
    }

    private val lock = Any()
    private var stream = spotter.createStream()

    /**
     * 送入音频并解码；命中唤醒词时回调一次。
     *
     * 顺序必须和官方示例一致：**先取结果，再判断是否可解码**。
     * 如果先 while(isReady) decode 完、最后只 getResult 一次，会漏掉解码过程中
     * 产生的命中（用官方 test_wavs 实测这种写法一个都检不到）。
     */
    fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            val current = stream
            current.acceptWaveform(samples, SAMPLE_RATE)
            while (true) {
                val keyword = spotter.getResult(current).keyword
                if (keyword.isNotEmpty()) {
                    val word = if (keyword.startsWith("@")) keyword.substring(1) else keyword
                    spotter.reset(current)
                    onKeyword(word)
                    break
                }
                if (!spotter.isReady(current)) break
                spotter.decode(current)
            }
        }
    }

    fun reset() = synchronized(lock) { spotter.reset(stream) }

    fun close() = synchronized(lock) { spotter.release() }
}
