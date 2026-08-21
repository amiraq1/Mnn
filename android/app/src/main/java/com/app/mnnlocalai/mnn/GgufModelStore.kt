package com.app.mnnlocalai.mnn

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class GgufModel(val id: String, val name: String, val file: File, val bytes: Long, val version: Int)

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
      GgufModel("gguf-${file.nameWithoutExtension}", file.nameWithoutExtension, file, file.length(), version)
    } catch (_: Exception) {
      preferences.edit().clear().apply()
      null
    }
  }

  fun importFromUri(uriString: String, displayName: String, expectedBytes: Long): GgufModel {
    if (!displayName.lowercase().endsWith(".gguf")) throw IllegalArgumentException("اختر ملفًا بامتداد GGUF")
    if (!root.exists() && !root.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد نماذج GGUF")
    if (expectedBytes > 0 && root.usableSpace < expectedBytes + 32L * 1024 * 1024) {
      throw IllegalStateException("لا توجد مساحة تخزين كافية لاستيراد النموذج")
    }
    val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
    val destination = File(root, "${System.currentTimeMillis()}-$safeName")
    val partial = File(root, "${destination.name}.part")
    val source = context.contentResolver.openInputStream(Uri.parse(uriString))
      ?: throw IllegalArgumentException("تعذر قراءة ملف GGUF المحدد")
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
      if (expectedBytes > 0 && partial.length() != expectedBytes) {
        throw IllegalStateException("لم يكتمل نسخ ملف GGUF؛ تحقق من التخزين ثم أعد المحاولة")
      }
      val version = validate(partial)
      if (!partial.renameTo(destination)) throw IllegalStateException("تعذر تثبيت ملف GGUF بعد التحقق")
      preferences.edit().putString("selectedName", destination.name).apply()
      return GgufModel("gguf-${destination.nameWithoutExtension}", displayName.removeSuffix(".gguf"), destination, destination.length(), version)
    } catch (error: Exception) {
      partial.delete()
      throw error
    }
  }

  fun clearSelected() {
    selected()?.file?.delete()
    preferences.edit().clear().apply()
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
}
