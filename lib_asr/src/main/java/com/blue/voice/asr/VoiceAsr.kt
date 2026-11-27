package com.blue.voice.asr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 语音转文字门面类：一个 init + 回调即可用，底层 [VoiceStreamRecognizer]/[VoiceMicCapture]
 * 仍保留供高级场景直接使用。
 *
 * 用法（Application.onCreate 里调用一次）：
 * ```
 * VoiceAsr.init(this, VoiceAsr.Config(), object : VoiceAsr.Callback {
 *     override fun onReady() { ... }
 *     override fun onPartial(text: String) { ... }
 *     override fun onFinal(text: String) { ... }
 *     override fun onError(message: String) { ... }
 * })
 * // 录音权限拿到后：
 * VoiceAsr.start()
 * // 结束：
 * VoiceAsr.stop()
 * ```
 *
 * 所有 JNI 与录音调用都在同一个串行后台线程执行；回调统一切回主线程。
 * 正常用法是在 Application.onCreate 里 init，故只持有 applicationContext（Application 类型）；
 * 显式加 @SuppressLint 兜底，避免 lint 沿静态引用链误判为 Context 泄漏。
 */
@SuppressLint("StaticFieldLeak")
object VoiceAsr {

    enum class State {
        IDLE,           // 未初始化
        INITIALIZING,   // 模型加载中
        READY,          // 模型就绪，可开始识别
        WAKING,         // 唤醒词监听中（KWS 常驻）
        LISTENING,      // 正在录音识别
        ERROR,          // 出错（详见 onError）
    }

    /**
     * 唤醒词配置。enable=false 时不启用唤醒，走手动 start() 流程。
     *
     * 自定义唤醒词有两种方式（二选一）：
     * - [keywords]：直接传已 token 化的字符串（默认内置「小卓小卓」），推荐；
     * - [keywordsFile]：传已 token 化的词表文件，高级场景。
     * 两者都为空时才用模型自带的 keywords.txt（含小爱同学/小艺小艺等，一般不想要）。
     * 注意：中文词无法直接传汉字，需先用 sherpa-onnx-cli text2token 转成 ppinyin token；
     * boosting/阈值请用 [keywordsScore]/[keywordsThreshold] 配置，不要在字符串里写 ":x #x"。
     */
    data class WakeWordConfig(
        val enable: Boolean = false,
        /** KWS 模型目录；null 表示用默认外部私有目录。 */
        val modelDir: File? = null,
        /**
         * 已 token 化的唤醒词字符串，每行一个词；非空时会生成词表文件并替换模型自带词表。
         * 默认是内置「小卓小卓」；传空字符串并配合 keywordsFile 可走文件方式。
         */
        val keywords: String = VoiceWakeWord.DEFAULT_KEYWORDS,
        /** 已 token 化的词表文件；仅当 keywords 为空时生效。 */
        val keywordsFile: File? = null,
        /** boosting 分数，越大越容易触发；官方默认 1.0，这里调到 2.0 提高灵敏度。 */
        val keywordsScore: Float = 2.0f,
        /** 触发阈值 0~1，越小越容易触发；官方默认 0.25，这里降到 0.15 提高灵敏度。 */
        val keywordsThreshold: Float = 0.15f,
        /**
         * 关键词后需要的尾随空白数；官方默认 1。
         * 调大会要求说完唤醒词后停顿更久、反而变钝；只有多个词表互相重叠时才需要调大。
         */
        val numTrailingBlanks: Int = 1,
        /** 解码 beam 大小；官方默认 4，调大可提升召回但增加 CPU 开销。 */
        val maxActivePaths: Int = 4,
        /** 命中唤醒词后是否自动进入 ASR 识别（false 时只回调 onWakeWord，由业务决定）。 */
        val autoStartAsr: Boolean = true,
    )

    data class Config(
        /** 模型目录；null 表示用默认外部私有目录，可传服务器下载后的任意目录。 */
        val modelDir: File? = null,
        val numThreads: Int = 2,
        /** 热词，换行分隔；可为空。 */
        val hotwords: String = "",
        /** init 时是否做 SHA-256 完整性校验（读取约 190MB，略耗时但能避免半截文件崩溃）。 */
        val verifyModel: Boolean = true,
        /** 唤醒词配置；默认关闭。 */
        val wakeWord: WakeWordConfig = WakeWordConfig(),
    )

    interface Callback {
        fun onState(state: State) {}
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** 命中唤醒词，参数为词条文本（如「小爱同学」）。 */
        fun onWakeWord(keyword: String) {}
        fun onError(message: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa-asr-worker") }

    @Volatile
    var state: State = State.IDLE
        private set

    private var appContext: Application? = null
    private var config: Config = Config()
    private var callback: Callback? = null
    private var recognizer: VoiceStreamRecognizer? = null
    private var mic: VoiceMicCapture? = null
    private var wakeWord: VoiceWakeWord? = null
    private var wakeMic: VoiceMicCapture? = null

    val isReady: Boolean get() = state == State.READY || state == State.LISTENING || state == State.WAKING

    /** 是否有录音权限；App 需在 start() 前自行弹框申请。 */
    fun hasRecordPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 模型目录描述，便于在页面上显示状态。 */
    fun modelInfo(context: Context): String = VoiceAsrModels.describe(context)

    /**
     * 在 Application.onCreate 调用一次。后台加载模型，成功回调 [Callback.onReady]，
     * 失败回调 [Callback.onError]（含具体原因）。
     */
    fun init(context: Application, config: Config = Config(), callback: Callback) {
        this.appContext = context
        this.config = config
        this.callback = callback
        setState(State.INITIALIZING)
        worker.execute {
            try {
                val app = this.appContext ?: error("application context 为空")
                val dir = config.modelDir ?: VoiceAsrModels.modelDir(app)
                val bad = if (config.verifyModel) {
                    VoiceAsrModels.verify(dir, app)
                } else {
                    VoiceAsrModels.missingFiles(dir)
                }
                require(bad.isEmpty()) {
                    "模型文件异常（缺失或损坏）: ${bad.joinToString()}；目录: ${dir.absolutePath}"
                }
                val engine = VoiceStreamRecognizer.create(
                    modelDir = dir,
                    numThreads = config.numThreads,
                    onPartial = { text -> dispatch { it.onPartial(text) } },
                    onFinal = { text -> dispatch { it.onFinal(text) } },
                )
                recognizer = engine

                // 唤醒词：加载 KWS 模型并开始常驻监听（麦克风被 KWS 占用）。
                if (config.wakeWord.enable) {
                    initWakeWordInternal(app, config.wakeWord)
                    startWakeListeningInternal()
                } else {
                    setState(State.READY)
                }
                dispatch { it.onReady() }
            } catch (e: Exception) {
                setState(State.ERROR)
                dispatch { it.onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * 开始识别。需已 [init] 成功且已取得录音权限；未就绪/无权限通过 onError 告知。
     * 若 KWS 正在监听，会先停掉 KWS 采集以释放麦克风。
     */
    fun start() {
        worker.execute {
            try {
                val engine = recognizer ?: error("模型尚未就绪，请先 init 成功后再 start")
                if (state == State.LISTENING) return@execute
                val app = appContext ?: error("application context 为空，请先 init")
                if (!hasRecordPermission(app)) {
                    throw SecurityException("缺少 RECORD_AUDIO 权限，请先申请运行时权限")
                }
                stopWakeMicInternal() // 释放麦克风给 ASR
                engine.cancel()
                engine.start(config.hotwords)
                val capture = VoiceMicCapture(app) { samples -> engine.accept(samples) }
                capture.start()
                mic = capture
                setState(State.LISTENING)
            } catch (e: Exception) {
                setState(if (recognizer == null) State.ERROR else idleStateAfterFailure())
                dispatch { it.onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** 停止识别，最终结果通过 [Callback.onFinal] 回调；随后恢复 KWS 监听。 */
    fun stop() {
        worker.execute {
            val capture = mic
            mic = null
            capture?.stop()
            val text = try {
                recognizer?.stop().orEmpty()
            } catch (e: Exception) {
                dispatch { it.onError(e.message ?: e.javaClass.simpleName) }
                ""
            }
            if (state == State.LISTENING) setState(State.READY)
            if (text.isNotEmpty()) dispatch { it.onFinal(text) }
            restoreWakeListeningInternal()
        }
    }

    /** 丢弃当前语音，不产生 final 回调；随后恢复 KWS 监听。 */
    fun cancel() {
        worker.execute {
            mic?.stop()
            mic = null
            recognizer?.cancel()
            if (state == State.LISTENING) setState(State.READY)
            restoreWakeListeningInternal()
        }
    }

    /** 释放引擎与麦克风（页面/进程退出时调用）；之后可再次 init。 */
    fun release() {
        worker.execute {
            mic?.stop()
            mic = null
            wakeMic?.stop()
            wakeMic = null
            wakeWord?.close()
            wakeWord = null
            recognizer?.close()
            recognizer = null
            setState(State.IDLE)
        }
    }

    /** 手动重新开启/关闭唤醒词监听（无需重新 init）。 */
    fun setWakeWordEnabled(enabled: Boolean) {
        worker.execute {
            if (enabled) {
                if (wakeWord == null) {
                    val app = appContext ?: return@execute
                    try {
                        initWakeWordInternal(app, config.wakeWord.copy(enable = true))
                    } catch (e: Exception) {
                        dispatch { it.onError("唤醒词模型加载失败: ${e.message}") }
                        return@execute
                    }
                }
                if (state == State.READY || state == State.IDLE) startWakeListeningInternal()
            } else {
                stopWakeMicInternal()
                wakeWord?.close()
                wakeWord = null
                if (state == State.WAKING) setState(State.READY)
            }
        }
    }

    // ---- 内部：唤醒词 ----

    private fun initWakeWordInternal(app: Application, wc: WakeWordConfig) {
        val dir = wc.modelDir ?: VoiceKwsModels.modelDir(app)
        wakeWord = VoiceWakeWord.create(
            modelDir = dir,
            keywords = wc.keywords,
            keywordsFile = wc.keywordsFile,
            keywordsCacheDir = File(app.cacheDir, "sherpa_kws"),
            keywordsScore = wc.keywordsScore,
            keywordsThreshold = wc.keywordsThreshold,
            numTrailingBlanks = wc.numTrailingBlanks,
            maxActivePaths = wc.maxActivePaths,
            onKeyword = { keyword ->
                // 运行在 KWS 采集线程；只投递回调，ASR 切换仍走 worker 串行。
                dispatch { it.onWakeWord(keyword) }
                if (wc.autoStartAsr) worker.execute { onWakeWordDetectedInternal() }
            },
        )
    }

    private fun startWakeListeningInternal() {
        val engine = wakeWord ?: return
        val app = appContext ?: return
        if (!hasRecordPermission(app)) {
            dispatch { it.onError("启用唤醒词缺少 RECORD_AUDIO 权限") }
            return
        }
        if (wakeMic?.isRunning == true) return
        val capture = VoiceMicCapture(app) { samples -> engine.accept(samples) }
        capture.start()
        wakeMic = capture
        setState(State.WAKING)
    }

    private fun stopWakeMicInternal() {
        wakeMic?.stop()
        wakeMic = null
    }

    /** 命中唤醒词后：停 KWS 采集，切到 ASR 识别（结果仍走 onFinal）。 */
    private fun onWakeWordDetectedInternal() {
        if (state == State.LISTENING) return
        val app = appContext ?: return
        val engine = recognizer ?: return
        stopWakeMicInternal()
        try {
            engine.cancel()
            engine.start(config.hotwords)
            val capture = VoiceMicCapture(app) { samples -> engine.accept(samples) }
            capture.start()
            mic = capture
            setState(State.LISTENING)
        } catch (e: Exception) {
            dispatch { it.onError(e.message ?: e.javaClass.simpleName) }
            restoreWakeListeningInternal()
        }
    }

    /** 识别结束后若启用了唤醒词，恢复 KWS 常驻监听。 */
    private fun restoreWakeListeningInternal() {
        if (config.wakeWord.enable && wakeWord != null && state != State.LISTENING) {
            startWakeListeningInternal()
        }
    }

    private fun idleStateAfterFailure(): State =
        if (config.wakeWord.enable && wakeWord != null) State.WAKING else State.READY

    private fun setState(next: State) {
        state = next
        mainHandler.post { callback?.onState(next) }
    }

    private fun dispatch(action: (Callback) -> Unit) {
        mainHandler.post { callback?.let(action) }
    }
}
