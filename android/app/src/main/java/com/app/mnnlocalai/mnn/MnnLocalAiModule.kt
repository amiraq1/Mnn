package com.app.mnnlocalai.mnn

import android.os.Debug
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MnnLocalAiModule(private val appContext: ReactApplicationContext) : ReactContextBaseJavaModule(appContext) {
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private val downloadRunning = AtomicBoolean(false)
  private val modelDirectory = File(appContext.filesDir, "models/qwen2.5-0.5b-mnn-q4")
  private val downloader = MnnModelDownloader(modelDirectory, ::emitDownloadProgress)
  @Volatile private var latestGenerationMs = 0L
  @Volatile private var latestGeneratedSteps = 0L
  @Volatile private var latestGenerationStopped = false

  init {
    nativeLibraryLoaded = try {
      System.loadLibrary("mnnlocalai")
      true
    } catch (error: UnsatisfiedLinkError) {
      nativeLoadError = error.message ?: "تعذر تحميل مكتبة MNN المحلية"
      false
    }
  }

  override fun getName() = "MnnLocalAi"

  @ReactMethod
  fun getModelStatus(promise: Promise) {
    ioExecutor.execute {
      val map = Arguments.createMap()
      val complete = downloader.isComplete()
      map.putString("state", when {
        downloadRunning.get() -> "downloading"
        complete -> "ready"
        else -> "missing"
      })
      map.putDouble("downloadedBytes", downloader.completedBytes().toDouble())
      map.putDouble("totalBytes", MnnModelRepository.totalBytes.toDouble())
      if (!nativeLibraryLoaded) map.putString("message", nativeLoadError)
      promise.resolve(map)
    }
  }

  @ReactMethod
  fun getPerformanceMetrics(promise: Promise) {
    ioExecutor.execute { promise.resolve(performanceMetricsMap()) }
  }

  @ReactMethod
  fun startModelDownload() {
    if (!downloadRunning.compareAndSet(false, true)) return
    ioExecutor.execute {
      try {
        downloader.downloadAll()
        emit("MnnLocalAiDownloadCompleted", Arguments.createMap())
      } catch (error: Exception) {
        emitError(null, error.message ?: "تعذر تنزيل النموذج")
      } finally {
        downloadRunning.set(false)
      }
    }
  }

  @ReactMethod
  fun initializeModel(promise: Promise) {
    ioExecutor.execute {
      if (!nativeLibraryLoaded) {
        promise.reject("MNN_RUNTIME_UNAVAILABLE", nativeLoadError)
        return@execute
      }
      if (!downloader.isComplete()) {
        promise.reject("MODEL_NOT_READY", "لا توجد نسخة مكتملة ومتحقق منها من النموذج المحلي")
        return@execute
      }
      val started = System.nanoTime()
      if (!nativeLoad(File(modelDirectory, "config.json").absolutePath)) {
        promise.reject("MODEL_LOAD_FAILED", "تعذر تحميل نموذج MNN من التخزين المحلي")
        return@execute
      }
      val loadMs = (System.nanoTime() - started) / 1_000_000
      val warmupStarted = System.nanoTime()
      if (!nativeWarmup()) {
        promise.reject("MODEL_WARMUP_FAILED", "تعذر تنفيذ warm-up للنموذج المحلي")
        return@execute
      }
      val map = Arguments.createMap()
      map.putDouble("loadMs", loadMs.toDouble())
      map.putDouble("warmupMs", ((System.nanoTime() - warmupStarted) / 1_000_000).toDouble())
      promise.resolve(map)
    }
  }

  @ReactMethod
  fun generate(prompt: String, runId: String, promise: Promise) {
    if (!nativeLibraryLoaded) {
      promise.reject("MNN_RUNTIME_UNAVAILABLE", nativeLoadError)
      return
    }
    promise.resolve(nativeGenerate(prompt, runId))
  }

  @ReactMethod
  fun stopGeneration() {
    if (nativeLibraryLoaded) nativeStopGeneration()
  }

  @ReactMethod
  fun releaseModel() {
    if (nativeLibraryLoaded) nativeRelease()
  }

  @ReactMethod
  fun deleteModel() {
    if (downloadRunning.get()) {
      emitError(null, "لا يمكن حذف النموذج أثناء التنزيل")
      return
    }
    ioExecutor.execute {
      if (nativeLibraryLoaded) nativeRelease()
      downloader.deleteAll()
      emit("MnnLocalAiModelDeleted", Arguments.createMap())
    }
  }

  fun emitTokenFromNative(runId: String, token: String) {
    emit("MnnLocalAiToken", Arguments.createMap().apply {
      putString("runId", runId)
      putString("token", token)
    })
  }

  fun emitGenerationCompletedFromNative(runId: String, stopped: Boolean, generationMs: Long, generatedSteps: Long) {
    latestGenerationMs = generationMs
    latestGeneratedSteps = generatedSteps
    latestGenerationStopped = stopped
    emit("MnnLocalAiGenerationCompleted", Arguments.createMap().apply {
      putString("runId", runId)
      putBoolean("stopped", stopped)
      putMetrics(this)
    })
  }

  fun emitErrorFromNative(runId: String?, message: String) = emitError(runId, message)

  private fun emitDownloadProgress(downloadedBytes: Long, totalBytes: Long, currentFile: String, phase: String) {
    emit("MnnLocalAiDownloadProgress", Arguments.createMap().apply {
      putDouble("downloadedBytes", downloadedBytes.toDouble())
      putDouble("totalBytes", totalBytes.toDouble())
      putString("currentFile", currentFile)
      putString("phase", phase)
    })
  }

  private fun emitError(runId: String?, message: String) {
    emit("MnnLocalAiError", Arguments.createMap().apply {
      putString("message", message)
      if (runId != null) putString("runId", runId)
    })
  }

  private fun performanceMetricsMap() = Arguments.createMap().apply { putMetrics(this) }

  private fun putMetrics(map: com.facebook.react.bridge.WritableMap) {
    val memoryInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memoryInfo)
    val javaHeapUsedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    val stepsPerSecond = if (latestGenerationMs > 0) latestGeneratedSteps * 1000.0 / latestGenerationMs else 0.0
    map.putBoolean("hasGeneration", latestGenerationMs > 0)
    map.putDouble("generationMs", latestGenerationMs.toDouble())
    map.putDouble("generatedSteps", latestGeneratedSteps.toDouble())
    map.putDouble("stepsPerSecond", stepsPerSecond)
    map.putBoolean("stopped", latestGenerationStopped)
    map.putDouble("totalPssKb", memoryInfo.totalPss.toDouble())
    map.putDouble("nativePssKb", memoryInfo.nativePss.toDouble())
    map.putDouble("javaHeapUsedKb", javaHeapUsedBytes / 1024.0)
  }

  private fun emit(eventName: String, payload: com.facebook.react.bridge.WritableMap) {
    if (appContext.hasActiveReactInstance()) {
      appContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, payload)
    }
  }

  private external fun nativeLoad(configPath: String): Boolean
  private external fun nativeWarmup(): Boolean
  private external fun nativeGenerate(prompt: String, runId: String): Boolean
  private external fun nativeStopGeneration()
  private external fun nativeRelease()

  private companion object {
    @Volatile private var nativeLibraryLoaded = false
    @Volatile private var nativeLoadError: String? = null
  }
}
