package com.blue.sherpa.asr

import android.content.Context
import java.io.File

/**
 * 中文 KWS（唤醒词）模型文件约定。
 *
 * 与 ASR 主模型同放在 app 外部私有目录的 sherpa 根下：
 * /sdcard/Android/data/<applicationId>/files/sherpa/<KWS_DIR_NAME>/
 *
 * 使用 int8 量化版（mobile），常驻监听约 5MB，远小于 190MB 的 ASR 模型。
 */
object SherpaKwsModels {
    const val MODEL_DIR_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"

    const val TOKENS = "tokens.txt"
    const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val KEYWORDS = "keywords.txt"

    val REQUIRED_FILES = listOf(TOKENS, ENCODER, DECODER, JOINER, KEYWORDS)

    /** KWS 模型目录：与 ASR 共用 sherpa 根目录。 */
    fun modelDir(context: Context): File = File(SherpaAsrModels.rootDir(context), MODEL_DIR_NAME)

    /** 返回缺失的文件名；全部存在时为空列表。 */
    fun missingFiles(modelDir: File): List<String> =
        REQUIRED_FILES.filterNot { File(modelDir, it).isFile }

    fun describe(context: Context): String {
        val dir = modelDir(context)
        val missing = missingFiles(dir)
        return if (missing.isEmpty()) {
            "OK: ${dir.absolutePath}"
        } else {
            "缺少唤醒词模型文件: ${missing.joinToString()}；目录: ${dir.absolutePath}"
        }
    }
}
