package com.app.mnnlocalai.mnn

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class GgufModel(val id: String, val name: String, val file: File, val bytes: Long, val version: Int)

data class RecommendedGgufModel(
  val id: String,
  val displayName: String,
  val description: String,
  val repository: String,
  val fileName: String,
  val bytes: Long,
  val sha256: String,
  val recommendedRamGb: Int,
) {
  fun downloadUrl() = "https://huggingface.co/$repository/resolve/main/$fileName?download=true"
}

object RecommendedGgufCatalog {
  val models = listOf(
    RecommendedGgufModel(
      "qwen-0.5b-q4km", "Qwen2.5 0.5B Instruct · Q4_K_M", "الأصغر والأخف؛ مناسب للتجربة والأجهزة ذات 4GB RAM.",
      "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032,
      "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 4,
    ),
    RecommendedGgufModel(
      "qwen-1.5b-q4km", "Qwen2.5 1.5B Instruct · Q4_K_M", "توازن أفضل بين الجودة والحجم؛ يوصى به للهواتف ذات 6GB RAM أو أكثر.",
      "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1117320736,
      "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 6,
    ),
    RecommendedGgufModel(
      "smollm2-1.7b-q4km", "SmolLM2 1.7B Instruct · Q4_K_M", "نموذج صغير متعدد الاستخدامات؛ يوصى به للهواتف ذات 6GB RAM أو أكثر.",
      "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF", "smollm2-1.7b-instruct-q4_k_m.gguf", 1055609536,
      "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33", 6,
    ),
  )

  fun byId(id: String) = models.firstOrNull { it.id == id }
}

class GgufModelStore(private val context: Context) {
  private val root = File(context.filesDir, "models/gguf")
  private val preferences = context.getSharedPreferences("gguf-model-store", Context.MODE_PRIVATE)

  fun selected(): GgufModel? {
    val name = preferences.getString("selectedName", null) ?: return null
    val file = File(root, name)
    if (!file.exists()) {
      preferences.edit().clear().apply()
      return null
    }
    return try {
      val version = validate(file)
      GgufModel(preferences.getString("selectedId", "gguf-${file.nameWithoutExtension}")!!, preferences.getString("selectedDisplayName", file.nameWithoutExtension)!!, file, file.length(), version)
    } catch (_: Exception) {
      preferences.edit().clear().apply()
      null
    }
  }

  fun catalogPartialBytes(model: RecommendedGgufModel): Long = File(root, "${model.id}.gguf.part").length().coerceAtMost(model.bytes)

  fun importFromUri(uriString: String, displayName: String, expectedBytes: Long): GgufModel {
    if (!displayName.lowercase().endsWith(".gguf")) throw IllegalArgumentException("اختر ملفًا بامتداد GGUF")
    ensureStorage(expectedBytes)
    val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
    val destination = File(root, "${System.currentTimeMillis()}-$safeName")
    val partial = File(root, "${destination.name}.part")
    val source = context.contentResolver.openInputStream(Uri.parse(uriString)) ?: throw IllegalArgumentException("تعذر قراءة ملف GGUF المحدد")
    try {
      BufferedInputStream(source).use { input ->
        FileOutputStream(partial).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
          }
          output.fd.sync()
        }
      }
      if (expectedBytes > 0 && partial.length() != expectedBytes) throw IllegalStateException("لم يكتمل نسخ ملف GGUF؛ تحقق من التخزين ثم أعد المحاولة")
      val version = validate(partial)
      if (!partial.renameTo(destination)) throw IllegalStateException("تعذر تثبيت ملف GGUF بعد التحقق")
      activate(destination, "custom-${destination.nameWithoutExtension}", displayName.removeSuffix(".gguf"))
      return GgufModel("custom-${destination.nameWithoutExtension}", displayName.removeSuffix(".gguf"), destination, destination.length(), version)
    } catch (error: Exception) {
      partial.delete()
      throw error
    }
  }

  fun downloadRecommended(model: RecommendedGgufModel, onProgress: (Long, Long, String, String) -> Unit): GgufModel {
    ensureStorage(model.bytes)
    val destination = File(root, "${model.id}.gguf")
    if (destination.exists() && destination.length() == model.bytes && sha256(destination).equals(model.sha256, true)) {
      val version = validate(destination)
      activate(destination, model.id, model.displayName)
      return GgufModel(model.id, model.displayName, destination, destination.length(), version)
    }
    if (destination.exists()) destination.delete()
    val partial = File(root, "${model.id}.gguf.part")
    var attempts = 0
    while (attempts < 2) {
      attempts += 1
      var offset = partial.length().coerceAtMost(model.bytes)
      val connection = (URL(model.downloadUrl()).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
      }
      try {
        val code = connection.responseCode
        if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
          partial.delete()
          offset = 0
          continue
        }
        if (code !in 200..299) throw IllegalStateException("تعذر تنزيل ${model.displayName}: HTTP $code")
        BufferedInputStream(connection.inputStream).use { input ->
          FileOutputStream(partial, offset > 0).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloaded = offset
            while (true) {
              val count = input.read(buffer)
              if (count <= 0) break
              output.write(buffer, 0, count)
              downloaded += count
              onProgress(downloaded.coerceAtMost(model.bytes), model.bytes, model.fileName, "downloading")
            }
            output.fd.sync()
          }
        }
        onProgress(partial.length().coerceAtMost(model.bytes), model.bytes, model.fileName, "verifying")
        if (partial.length() != model.bytes || !sha256(partial).equals(model.sha256, true)) {
          partial.delete()
          if (attempts == 2) throw IllegalStateException("فشل التحقق من SHA-256 لنموذج ${model.displayName}")
          continue
        }
        val version = validate(partial)
        if (!partial.renameTo(destination)) throw IllegalStateException("تعذر تثبيت نموذج GGUF بعد التحقق")
        activate(destination, model.id, model.displayName)
        return GgufModel(model.id, model.displayName, destination, destination.length(), version)
      } finally {
        connection.disconnect()
      }
    }
    throw IllegalStateException("تعذر تنزيل نموذج GGUF")
  }

  fun clearSelected() {
    selected()?.file?.delete()
    preferences.edit().clear().apply()
  }

  private fun ensureStorage(requiredBytes: Long) {
    if (!root.exists() && !root.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد نماذج GGUF")
    if (requiredBytes > 0 && root.usableSpace < requiredBytes + 32L * 1024 * 1024) throw IllegalStateException("لا توجد مساحة تخزين كافية للنموذج")
  }

  private fun activate(file: File, id: String, displayName: String) {
    preferences.edit().putString("selectedName", file.name).putString("selectedId", id).putString("selectedDisplayName", displayName).apply()
  }

  private fun validate(file: File): Int {
    if (file.length() < 24) throw IllegalArgumentException("ملف GGUF صغير أو غير صالح")
    RandomAccessFile(file, "r").use { input ->
      val magic = ByteArray(4)
      input.readFully(magic)
      if (String(magic, StandardCharsets.US_ASCII) != "GGUF") throw IllegalArgumentException("الملف ليس نموذج GGUF صالحًا")
      val version = Integer.reverseBytes(input.readInt())
      if (version !in 2..3) throw IllegalArgumentException("إصدار GGUF غير مدعوم: $version")
      return version
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count <= 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
