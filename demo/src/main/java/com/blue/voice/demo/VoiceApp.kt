package com.blue.voice.demo

import android.app.Application
import com.blue.voice.asr.VoiceAsr
import com.blue.voice.tts.VoiceTts

/** App 创建时初始化一次 ASR；页面通过 [AsrBus] 注册回调接收结果。 */
class VoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启用唤醒词：默认内置「小卓小卓」，已替换模型自带的词表（不再响应小爱同学/小艺小艺）。
        // 自定义唤醒词：把 keywords 换成你自己的 ppinyin token 串即可，例如：
        //   keywords = "n ǐ h ǎo zh uó zh uó @你好卓卓"
        // 中文词无法直接写汉字，需用 sherpa-onnx-cli text2token 生成 token（见 README）。
        val config = VoiceAsr.Config(
            wakeWord = VoiceAsr.WakeWordConfig(
                enable = true,
                // keywords = VoiceWakeWord.DEFAULT_KEYWORDS, // 默认值「小卓小卓」，无需显式设置
                autoStartAsr = true,
                // 灵敏度参数（库内默认值，写在这里方便调）：
                keywordsScore = 2.0f,       // 越大越易触发
                keywordsThreshold = 0.15f,  // 越小越易触发；误唤醒多就调回 0.2~0.25
                numTrailingBlanks = 1,      // 调大反而更钝（说完要停更久）
                maxActivePaths = 4,         // 仍不够灵敏可试 8
            ),
        )
        VoiceAsr.init(this, config, AsrBus.relay)
    }
}

/** 把全局回调转发给当前页面注册的监听者。 */
object AsrBus {
    @Volatile
    var listener: VoiceAsr.Callback? = null

    val relay = object : VoiceAsr.Callback {
        override fun onState(state: VoiceAsr.State) { listener?.onState(state) }
        override fun onReady() { listener?.onReady() }
        override fun onPartial(text: String) { listener?.onPartial(text) }
        override fun onFinal(text: String) { listener?.onFinal(text) }
        override fun onWakeWord(keyword: String) { listener?.onWakeWord(keyword) }
        override fun onError(message: String) { listener?.onError(message) }
    }
}

/** TTS 回调转发；TTS 模型大，由 TtsActivity 首次进入时再 init。 */
object TtsBus {
    @Volatile
    var listener: VoiceTts.Callback? = null

    val relay = object : VoiceTts.Callback {
        override fun onState(state: VoiceTts.State) { listener?.onState(state) }
        override fun onReady() { listener?.onReady() }
        override fun onFirstAudioLatency(latencyMs: Int) { listener?.onFirstAudioLatency(latencyMs) }
        override fun onStart(text: String, sampleRate: Int, durationMs: Int) {
            listener?.onStart(text, sampleRate, durationMs)
        }
        override fun onComplete(text: String, frames: Int) { listener?.onComplete(text, frames) }
        override fun onError(message: String) { listener?.onError(message) }
    }
}
