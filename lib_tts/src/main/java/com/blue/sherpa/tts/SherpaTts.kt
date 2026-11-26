package com.blue.sherpa.tts

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * 离线 TTS 门面。
 *
 * 与 [SherpaAsr] 保持同样用法：一次 init，后台线程合成/播放，回调回主线程。
 * 模型 163MB，不进 assets / APK，从 [SherpaTtsModels.defaultModelDir] 加载。
 */
@SuppressLint("StaticFieldLeak") // 只持有 Application，不会泄漏 Activity
object SherpaTts {
    enum class State { IDLE, INITIALIZING, READY, SYNTHESIZING, PLAYING, ERROR }

    interface Callback {
        fun onState(state: State) = Unit
        fun onReady() = Unit
        fun onStart(text: String, sampleRate: Int, durationMs: Int) = Unit
        fun onComplete(text: String, frames: Int) = Unit
        fun onError(message: String) = Unit
    }

    /**
     * @param modelDir 模型目录；默认 `<externalFilesDir>/sherpa/vits-melo-tts-zh_en`
     * @param ruleFsts 可选的数字/日期/电话 FST 文件名，相对 [modelDir]；默认不启用
     */
    data class Config(
        val modelDir: File? = null,
        val modelName: String = SherpaTtsModels.MODEL_FILE,
        val lexicon: String = SherpaTtsModels.LEXICON_FILE,
        val ruleFsts: List<String> = emptyList(),
        val numThreads: Int = 2,
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

    /** 可重复调用：已初始化时只替换回调，不会重复加载 163MB 模型。 */
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
                val ruleFsts = config.ruleFsts.joinToString(",") { name ->
                    val f = File(name)
                    if (f.isAbsolute) f.absolutePath else File(dir, name).absolutePath
                }
                val engineConfig = getOfflineTtsConfig(
                    modelDir = dir.absolutePath,
                    modelName = config.modelName,
                    lexicon = config.lexicon,
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

    /** 合成并播放；重复调用会取消上一段播放。 */
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
        val seq = ++generationSeq
        worker.execute {
            try {
                if (seq != generationSeq) return@execute
                setState(State.SYNTHESIZING)
                val audio = engine.generate(text, speakerId, speed)
                if (seq != generationSeq) return@execute

                val p = SherpaTtsPlayer(audio.sampleRate)
                player = p
                setState(State.PLAYING)
                val durationMs = (audio.samples.size / audio.sampleRate.toFloat() * 1000).toInt()
                mainHandler.post { callback?.onStart(text, audio.sampleRate, durationMs) }

                p.play(
                    samples = audio.samples,
                    onDone = { frames ->
                        if (seq == generationSeq && player === p) {
                            setState(State.READY)
                            mainHandler.post { callback?.onComplete(text, frames) }
                        }
                    },
                    onError = { message ->
                        if (seq == generationSeq) fail("播放失败：$message")
                    },
                )
            } catch (t: Throwable) {
                if (seq == generationSeq) fail("合成失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
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
