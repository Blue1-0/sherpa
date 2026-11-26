package com.blue.sherpa.asr

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 中英双语流式 ASR 模型文件约定。
 *
 * 模型不进 assets / APK，默认放在 app 的外部私有目录：
 * /sdcard/Android/data/<applicationId>/files/sherpa/<MODEL_DIR_NAME>/
 *
 * 也可以通过 [SherpaStreamRecognizer.create] 传入任意可读取的目录，
 * 便于放在公共目录或由服务器先行下载。
 */
object SherpaAsrModels {
    const val ROOT_DIR_NAME = "sherpa"
    const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"

    const val TOKENS = "tokens.txt"
    const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    const val DECODER = "decoder-epoch-99-avg-1.onnx"
    const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"

    val REQUIRED_FILES = listOf(TOKENS, ENCODER, DECODER, JOINER)

    /** 当前 app 的模型根目录，无需存储权限。 */
    fun rootDir(context: Context): File = File(context.getExternalFilesDir(null), ROOT_DIR_NAME)

    fun modelDir(context: Context): File = File(rootDir(context), MODEL_DIR_NAME)

    /** 返回缺失的文件名；全部存在时为空列表。 */
    fun missingFiles(modelDir: File): List<String> =
        REQUIRED_FILES.filterNot { File(modelDir, it).isFile }

    /**
     * 按 assets/model_manifest.json 里的 sha256 校验文件完整性。
     * 返回校验不通过（缺失、大小不符、哈希不符）的文件名；清单不存在时退化为只查存在性。
     * 该操作会读取约 190MB，必须在后台线程调用。
     */
    fun verify(modelDir: File, context: Context): List<String> {
        val manifest = readManifest(context)
        val missing = missingFiles(modelDir)
        if (manifest == null) return missing
        return REQUIRED_FILES.filterNot { name ->
            val file = File(modelDir, name)
            val meta = manifest[name] as? org.json.JSONObject ?: return@filterNot false
            val size = if (meta.has("size")) meta.getLong("size") else -1L
            val sha = if (meta.has("sha256")) meta.getString("sha256") else ""
            file.isFile && (size < 0 || file.length() == size) &&
                (sha.isEmpty() || sha256(file).equals(sha, ignoreCase = true))
        }
    }

    fun describe(context: Context): String {
        val dir = modelDir(context)
        val missing = missingFiles(dir)
        return if (missing.isEmpty()) {
            "OK: ${dir.absolutePath}"
        } else {
            "缺少模型文件: ${missing.joinToString()}；目录: ${dir.absolutePath}"
        }
    }

    private fun readManifest(context: Context): org.json.JSONObject? = try {
        val input: InputStream = context.assets.open("model_manifest.json")
        val text = input.bufferedReader().use { it.readText() }
        org.json.JSONObject(text).optJSONObject("files")
    } catch (_: Exception) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
