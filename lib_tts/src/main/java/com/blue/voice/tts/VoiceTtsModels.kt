package com.blue.voice.tts

import android.content.Context
import java.io.File

/**
 * TTS 模型的外部目录约定。
 *
 * 模型不进 assets / APK：下载脚本把它放到
 * `<externalFilesDir>/sherpa/matcha-icefall-zh-en/`，应用启动时从该路径加载。
 *
 * 默认用 Matcha（非自回归，3 步解码）中英双语模型：
 * 实测合成耗时约为 VITS 的 1/10；中文词典 + 英文词典并列实现中英混读。
 */
object VoiceTtsModels {
    const val ROOT_DIR = "sherpa"
    const val MODEL_DIR_NAME = "matcha-icefall-zh-en"
    const val ACOUSTIC_MODEL_FILE = "model-steps-3.onnx"
    const val VOCODER_FILE = "vocos-16khz-univ.onnx"
    const val LEXICON_ZH = "lexicon.txt"
    const val LEXICON_EN = "lexicon-en.txt"
    const val TOKENS_FILE = "tokens.txt"

    /** VITS 备用模型的声学文件名（用户自行换模型时用）。 */
    const val MODEL_FILE = "model.onnx"

    val DEFAULT_LEXICONS = listOf(LEXICON_ZH, LEXICON_EN)
    val DEFAULT_RULE_FSTS = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")

    /** 应用私有外部目录：无需存储权限，卸载应用时系统清理。 */
    fun defaultModelDir(context: Context): File =
        File(context.getExternalFilesDir(null), "$ROOT_DIR/$MODEL_DIR_NAME")

    /** 引擎必需的文件；缺少任意一个都不能 init。 */
    fun requiredFiles(dir: File): List<File> = listOf(
        File(dir, ACOUSTIC_MODEL_FILE),
        File(dir, VOCODER_FILE),
        File(dir, LEXICON_ZH),
        File(dir, LEXICON_EN),
        File(dir, TOKENS_FILE),
    )

    fun describe(dir: File): String {
        val missing = requiredFiles(dir).filterNot { it.exists() }.joinToString { it.name }
        return if (missing.isEmpty()) {
            "matcha-icefall-zh-en（中英双语，约 130MB，外部目录）"
        } else {
            "matcha-icefall-zh-en（缺少：$missing）"
        }
    }
}
