package com.blue.sherpa.tts

import android.content.Context
import java.io.File

/**
 * TTS 模型的外部目录约定。
 *
 * 模型不进 assets / APK：下载脚本把它放到
 * `<externalFilesDir>/sherpa/vits-melo-tts-zh_en/`，应用启动时从该路径加载。
 */
object SherpaTtsModels {
    const val ROOT_DIR = "sherpa"
    const val MODEL_DIR_NAME = "vits-melo-tts-zh_en"
    const val MODEL_FILE = "model.onnx"
    const val LEXICON_FILE = "lexicon.txt"
    const val TOKENS_FILE = "tokens.txt"

    /** 应用私有外部目录：无需存储权限，卸载应用时系统清理。 */
    fun defaultModelDir(context: Context): File =
        File(context.getExternalFilesDir(null), "$ROOT_DIR/$MODEL_DIR_NAME")

    /** 引擎必需的文件；缺少任意一个都不能 init。 */
    fun requiredFiles(dir: File): List<File> = listOf(
        File(dir, MODEL_FILE),
        File(dir, LEXICON_FILE),
        File(dir, TOKENS_FILE),
    )

    fun describe(dir: File): String {
        val missing = requiredFiles(dir).filterNot { it.exists() }.joinToString { it.name }
        return if (missing.isEmpty()) {
            "vits-melo-tts-zh_en（中英双语，163MB，外部目录）"
        } else {
            "vits-melo-tts-zh_en（缺少：$missing）"
        }
    }
}
