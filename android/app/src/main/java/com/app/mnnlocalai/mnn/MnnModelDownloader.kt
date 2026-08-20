package com.app.mnnlocalai.mnn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelArtifact(
  val name: String,
  val bytes: Long,
  val sha256: String,
)

object MnnModelRepository {
  private const val baseUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main"

  val artifacts = listOf(
    ModelArtifact("config.json", 159, "7636b063425bdbc0e2e429cb23af7f594b5ba145bab2045dfda852416d9285de"),
    ModelArtifact("embeddings_bf16.bin", 272269312, "4e96b0df6d274768cbb7e72404011853d23349999b658dc2f4dfb3c431ea223f"),
    ModelArtifact("llm.mnn", 566264, "480da511e603bd82f8d4af4e1f778ad72baadf8307f3585465ad9a94daca1a88"),
    ModelArtifact("llm.mnn.json", 2808932, "245ce4289f456dcb371a8f8deabf75c3c4ee75f34b19e0d9723ba09b2fbacf8c"),
    ModelArtifact("llm.mnn.weight", 277967498, "7ed0f4dcdd31dca15fcb548d2fc8b63b0014031fbd5f627508435726f90c75da"),
    ModelArtifact("llm_config.json", 272, "ec05709b4261d59b510a0b7a636c6dcb6c5635c08fee7eb3c4f04188b509694b"),
    ModelArtifact("tokenizer.txt", 3193477, "b86f1298a0d6a1b2f312946c2f674f883f1d134ccabc79c42dd4c6b5beadf37b"),
  )

  val totalBytes: Long = artifacts.sumOf { it.bytes }
  fun urlFor(artifact: ModelArtifact) = "$baseUrl/${artifact.name}?download=true"
}

class MnnModelDownloader(
  private val root: File,
  private val onProgress: (downloadedBytes: Long, totalBytes: Long, currentFile: String, phase: String) -> Unit,
) {
  private val stateFile = File(root, "download-state.json")

  fun isComplete(): Boolean = MnnModelRepository.artifacts.all { artifact ->
    val finalFile = File(root, artifact.name)
    finalFile.exists() && finalFile.length() == artifact.bytes && sha256(finalFile).equals(artifact.sha256, true)
  }

  fun completedBytes(): Long = MnnModelRepository.artifacts.sumOf { artifact ->
    val finalFile = File(root, artifact.name)
    when {
      finalFile.exists() -> finalFile.length().coerceAtMost(artifact.bytes)
      else -> File(root, "${artifact.name}.part").length().coerceAtMost(artifact.bytes)
    }
  }

  fun deleteAll() {
    MnnModelRepository.artifacts.forEach { artifact ->
      File(root, artifact.name).delete()
      File(root, "${artifact.name}.part").delete()
    }
    stateFile.delete()
  }

  fun downloadAll() {
    if (!root.exists() && !root.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد النموذج المحلي")
    MnnModelRepository.artifacts.forEach { artifact ->
      val finalFile = File(root, artifact.name)
      if (finalFile.exists() && finalFile.length() == artifact.bytes && sha256(finalFile).equals(artifact.sha256, true)) return@forEach
      if (finalFile.exists()) finalFile.delete()
      downloadArtifact(artifact)
    }
    persistState("ready", MnnModelRepository.totalBytes, "")
  }

  private fun downloadArtifact(artifact: ModelArtifact) {
    val partFile = File(root, "${artifact.name}.part")
    var attempts = 0
    while (attempts < 2) {
      attempts += 1
      var offset = partFile.length().coerceAtMost(artifact.bytes)
      val connection = (URL(MnnModelRepository.urlFor(artifact)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
      }
      try {
        val code = connection.responseCode
        if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
          partFile.delete()
          offset = 0
          persistState("downloading", completedBytes(), artifact.name)
          continue
        }
        if (offset == 0L && code !in 200..299) throw IllegalStateException("تعذر تنزيل ${artifact.name}: HTTP $code")
        if (offset > 0 && code !in 200..299) throw IllegalStateException("تعذر استئناف ${artifact.name}: HTTP $code")

        BufferedInputStream(connection.inputStream).use { input ->
          FileOutputStream(partFile, offset > 0).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloadedForArtifact = offset
            var bytesSinceStateWrite = 0L
            while (true) {
              val count = input.read(buffer)
              if (count <= 0) break
              output.write(buffer, 0, count)
              downloadedForArtifact += count
              bytesSinceStateWrite += count
              val aggregate = completedBytes()
              onProgress(aggregate.coerceAtMost(MnnModelRepository.totalBytes), MnnModelRepository.totalBytes, artifact.name, "downloading")
              if (bytesSinceStateWrite >= 512 * 1024) {
                persistState("downloading", aggregate, artifact.name)
                bytesSinceStateWrite = 0
              }
            }
            output.fd.sync()
          }
        }
        onProgress(completedBytes(), MnnModelRepository.totalBytes, artifact.name, "verifying")
        if (partFile.length() != artifact.bytes || !sha256(partFile).equals(artifact.sha256, true)) {
          partFile.delete()
          if (attempts == 2) throw IllegalStateException("فشل التحقق من SHA-256 للملف ${artifact.name}")
          continue
        }
        val finalFile = File(root, artifact.name)
        if (!partFile.renameTo(finalFile)) throw IllegalStateException("تعذر تثبيت الملف ${artifact.name} بعد التحقق")
        persistState("downloading", completedBytes(), artifact.name)
        return
      } finally {
        connection.disconnect()
      }
    }
  }

  private fun persistState(state: String, bytes: Long, currentFile: String) {
    val files = JSONArray().apply {
      MnnModelRepository.artifacts.forEach { artifact ->
        put(JSONObject().apply {
          put("name", artifact.name)
          put("offset", File(root, "${artifact.name}.part").length())
        })
      }
    }
    val content = JSONObject().apply {
      put("state", state)
      put("downloadedBytes", bytes)
      put("currentFile", currentFile)
      put("files", files)
    }.toString()
    stateFile.writeText(content)
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
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }
}
