package com.blue.sherpa.tts

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * 离线 TTS 门面。
 *
 * 与 [SherpaAsr] 保持同样用法：一次 init，后台线程合成/播放，回调回主线程。
 * 默认用 Matcha 中英双语模型（非自回归，合成速度约为 VITS 的 10 倍），
 * 模型不进 assets / APK，从 [SherpaTtsModels.defaultModelDir] 加载。
 */
@SuppressLint("StaticFieldLeak") // 只持有 Application，不会泄漏 Activity
object SherpaTts {
    enum class State { IDLE, INITIALIZING, READY, SYNTHESIZING, PLAYING, ERROR }

    interface Callback {
        fun onState(state: State) = Unit
        fun onReady() = Unit
        /** 从 [synthesize] 被调用（点击按钮）到首句开始播放的耗时。 */
        fun onFirstAudioLatency(latencyMs: Int) = Unit
        fun onStart(text: String, sampleRate: Int, durationMs: Int) = Unit
        fun onComplete(text: String, frames: Int) = Unit
        fun onError(message: String) = Unit
    }

    private const val TAG = "SherpaTts"

    /**
     * @param modelDir 模型目录；默认 `<externalFilesDir>/sherpa/matcha-icefall-zh-en`
     * @param acousticModelName Matcha 声学模型文件名；为空则用 VITS [modelName]
     * @param vocoder Matcha 声码器路径（绝对路径或多个文件名时用逗号分隔）
     * @param lexicons 发音词典文件名列表；中文词典 + 英文词典实现中英混读
     * @param ruleFsts 数字/日期/电话 FST 文件名，相对 [modelDir]
     */
    data class Config(
        val modelDir: File? = null,
        val modelName: String = SherpaTtsModels.MODEL_FILE,
        val acousticModelName: String = SherpaTtsModels.ACOUSTIC_MODEL_FILE,
        val vocoder: String = SherpaTtsModels.VOCODER_FILE,
        val lexicons: List<String> = SherpaTtsModels.DEFAULT_LEXICONS,
        val ruleFsts: List<String> = SherpaTtsModels.DEFAULT_RULE_FSTS,
        /** 推理线程数；实测 4 线程比 2 线程快约 20~30%，PDA 多核建议 4。 */
        val numThreads: Int = 4,
        val speakerId: Int = 0,
        val speed: Float = 1.0f,
    )

    @Volatile
    var state: State = State.IDLE
        private set

    val isReady: Boolean
        get() = state == State.READY || state == State.SYNTHESIZING || state == State.PLAYING

    private var app: Application? = null
    private var activeConfig = Config()
    private var callback: Callback? = null
    private var tts: OfflineTts? = null
    private var player: SherpaTtsPlayer? = null
    private var generationSeq = 0

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sherpa-tts-worker") }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 可重复调用：已初始化时只替换回调，不会重复加载模型。 */
    fun init(context: Application, config: Config = Config(), callback: Callback) {
        this.callback = callback
        if (state == State.INITIALIZING || tts != null) {
            if (tts != null) mainHandler.post { callback.onReady() }
            return
        }
        app = context
        activeConfig = config
        setState(State.INITIALIZING)
        worker.execute {
            try {
                val dir = config.modelDir ?: SherpaTtsModels.defaultModelDir(context)
                val missing = SherpaTtsModels.requiredFiles(dir).filterNot { it.exists() }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("缺少 TTS 模型文件：${missing.joinToString { it.name }}")
                }
                // getOfflineTtsConfig 对含逗号的 lexicon 直接使用完整路径，故这里手动拼绝对路径。
                val lexicon = config.lexicons.joinToString(",") { name ->
                    val f = File(name)
                    if (f.isAbsolute) f.absolutePath else File(dir, name).absolutePath
                }
                // vocoder 同理：含逗号时按完整路径处理，单个文件名则由 getOfflineTtsConfig 拼接。
                val vocoder = if ("," in config.vocoder || File(config.vocoder).isAbsolute) {
                    config.vocoder
                } else {
                    File(dir, config.vocoder).absolutePath
                }
                val ruleFsts = config.ruleFsts.joinToString(",") { name ->
                    val f = File(name)
                    if (f.isAbsolute) f.absolutePath else File(dir, name).absolutePath
                }
                val engineConfig = getOfflineTtsConfig(
                    modelDir = dir.absolutePath,
                    modelName = if (config.acousticModelName.isEmpty()) config.modelName else "",
                    acousticModelName = config.acousticModelName,
                    vocoder = vocoder,
                    lexicon = lexicon,
                    ruleFsts = ruleFsts,
                    numThreads = config.numThreads,
                )
                tts = OfflineTts(config = engineConfig)
                setState(State.READY)
                mainHandler.post { callback.onReady() }
            } catch (t: Throwable) {
                fail("TTS 初始化失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 合成并播放；重复调用会取消上一段播放。
     *
     * 按标点分句后逐句合成、逐句写入 AudioTrack：第一句生成（约 0.3s）就开始出声，
     * 后续句子在播放期间继续合成，用户感知等待大幅缩短。
     */
    fun synthesize(
        text: String,
        speakerId: Int = activeConfig.speakerId,
        speed: Float = activeConfig.speed,
    ) {
        if (text.isBlank()) {
            fail("合成文本为空")
            return
        }
        if (state == State.IDLE || state == State.INITIALIZING || state == State.ERROR) {
            fail("TTS 引擎尚未就绪")
            return
        }
        val engine = tts
        if (engine == null) {
            fail("TTS 引擎尚未就绪")
            return
        }
        // 点击按钮的时刻：把排队等待、分句、合成、写入缓冲的时间都算进去。
        val startMs = SystemClock.elapsedRealtime()
        stop() // 取消上一段播放
        val seq = ++generationSeq
        val sentences = splitText(text)
        worker.execute {
            try {
                if (seq != generationSeq) return@execute
                setState(State.SYNTHESIZING)
                val tWorker = SystemClock.elapsedRealtime()

                val p = SherpaTtsPlayer(engine.sampleRate())
                player = p
                p.open()
                val tOpen = SystemClock.elapsedRealtime()

                var totalFrames = 0
                var firstSentence = true
                for (sentence in sentences) {
                    if (seq != generationSeq) return@execute
                    val audio = engine.generate(sentence, speakerId, speed)
                    val tGen = SystemClock.elapsedRealtime()
                    if (seq != generationSeq) return@execute
                    val written = p.write(audio.samples)
                    val tWrite = SystemClock.elapsedRealtime()
                    if (firstSentence) {
                        Log.d(TAG, "首段明细：排队=${tWorker - startMs}ms，open=${tOpen - tWorker}ms，generate=${tGen - tOpen}ms，write=${tWrite - tGen}ms（音频 ${(audio.samples.size / audio.sampleRate.toFloat() * 1000).toInt()}ms）")
                    }
                    totalFrames += written
                    if (firstSentence && written > 0) {
                        firstSentence = false
                        val latencyMs = (SystemClock.elapsedRealtime() - startMs).toInt()
                        Log.d(TAG, "首句出声耗时=${latencyMs}ms（点击→播放开始），文本长度=${text.length}，分句数=${sentences.size}，speed=$speed")
                        setState(State.PLAYING)
                        val firstMs = (totalFrames * 1000f / audio.sampleRate).toInt()
                        mainHandler.post {
                            callback?.onFirstAudioLatency(latencyMs)
                            callback?.onStart(text, audio.sampleRate, firstMs)
                        }
                    }
                }
                if (seq != generationSeq) return@execute

                p.finish()
                if (seq == generationSeq && player === p) {
                    setState(State.READY)
                    mainHandler.post { callback?.onComplete(text, totalFrames) }
                }
            } catch (t: Throwable) {
                if (seq == generationSeq) fail("合成/播放失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 按中文/英文标点切句，并把过短片段合并，避免每个字都单独合成。 */
    private fun splitText(text: String): List<String> {
        val pieces = text.split(Regex("(?<=[，。！？；,.!?;])"))
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        for (piece in pieces) {
            if (piece.isBlank()) continue
            if (buffer.isNotBlank() && buffer.length + piece.length > 40) {
                result.add(buffer.toString())
                buffer.setLength(0)
            }
            buffer.append(piece)
        }
        if (buffer.isNotBlank()) result.add(buffer.toString())
        return result.ifEmpty { listOf(text) }
    }

    /** 停止当前播放并回到 READY，不释放引擎。 */
    fun stop() {
        generationSeq++
        player?.stopAndRelease()
        player = null
        if (tts != null) setState(State.READY)
    }

    /** 释放引擎；之后可再次 [init]。 */
    fun release() {
        generationSeq++
        player?.stopAndRelease()
        player = null
        setState(State.IDLE)
        worker.execute {
            try {
                tts?.release()
            } catch (_: Throwable) {
            }
            tts = null
            app = null
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    fun modelInfo(context: Context): String =
        SherpaTtsModels.describe(SherpaTtsModels.defaultModelDir(context))

    fun hasModel(context: Context): Boolean =
        SherpaTtsModels.requiredFiles(SherpaTtsModels.defaultModelDir(context)).all { it.exists() }

    private fun setState(newState: State) {
        state = newState
        mainHandler.post { callback?.onState(newState) }
    }

    private fun fail(message: String) {
        setState(State.ERROR)
        mainHandler.post { callback?.onError(message) }
    }
}
