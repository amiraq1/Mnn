package com.app.mnnlocalai.mnn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.SystemClock
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
  private val downloadPauseController = DownloadPauseController()
  private val downloadRateLimiter = DownloadRateLimiter()
  private val downloadPreferences = DownloadPreferencesStore(appContext)
  private val modelDirectory = File(appContext.filesDir, "models/qwen2.5-0.5b-mnn-q4")
  private val downloader = MnnModelDownloader(modelDirectory, ::emitDownloadProgress, downloadPauseController, downloadRateLimiter)
  private val ggufStore = GgufModelStore(appContext)
  @Volatile private var activeGgufDownload: RecommendedGgufModel? = null
  @Volatile private var downloadPaused = false
  @Volatile private var latestDownloadPhase = ""
  @Volatile private var latestDownloadFile = ""
  @Volatile private var latestDownloadSpeedBytesPerSecond = 0.0
  @Volatile private var latestDownloadEtaSeconds = -1L
  @Volatile private var downloadSampleAtMs = 0L
  @Volatile private var downloadSampleBytes = 0L
  @Volatile private var downloadStartedAtMs = 0L
  @Volatile private var downloadStartedBytes = 0L
  @Volatile private var downloadTotalBytes = 0L
  @Volatile private var downloadModelName = ""
  @Volatile private var downloadEngine = "mnn"
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
      val gguf = ggufStore.selected()
      val activeCatalogModel = activeGgufDownload
      val map = Arguments.createMap()
      val complete = downloader.isComplete()
      map.putString("state", when {
        downloadPaused -> "paused"
        downloadRunning.get() -> "downloading"
        gguf != null -> "ready"
        complete -> "ready"
        else -> "missing"
      })
      map.putDouble("downloadedBytes", (if (activeCatalogModel != null) ggufStore.catalogPartialBytes(activeCatalogModel) else gguf?.bytes ?: downloader.completedBytes()).toDouble())
      map.putDouble("totalBytes", (activeCatalogModel?.bytes ?: gguf?.bytes ?: MnnModelRepository.totalBytes).toDouble())
      map.putString("engine", if (activeCatalogModel != null || gguf != null) "gguf" else "mnn")
      map.putString("format", if (activeCatalogModel != null) "GGUF" else if (gguf != null) "GGUF v${gguf.version}" else "MNN")
      map.putString("modelName", activeCatalogModel?.displayName ?: gguf?.name ?: "Qwen2.5 0.5B — MNN Q4")
      if (downloadPaused) map.putString("message", "تم إيقاف التنزيل مؤقتًا. استأنفه عند جاهزية الشبكة.")
      if (!nativeLibraryLoaded) map.putString("message", nativeLoadError)
      promise.resolve(map)
    }
  }

  @ReactMethod
  fun getPerformanceMetrics(promise: Promise) {
    ioExecutor.execute { promise.resolve(performanceMetricsMap()) }
  }

  @ReactMethod
  fun getDownloadSettings(promise: Promise) {
    promise.resolve(downloadSettingsMap())
  }

  @ReactMethod
  fun setDownloadSettings(completionNotificationsEnabled: Boolean, cellularSpeedLimitKbps: Double, promise: Promise) {
    downloadPreferences.updateSettings(completionNotificationsEnabled, cellularSpeedLimitKbps.toInt())
    promise.resolve(downloadSettingsMap())
  }

  @ReactMethod
  fun getDownloadHistory(promise: Promise) {
    val history = Arguments.createArray()
    downloadPreferences.history().forEach { entry ->
      history.pushMap(Arguments.createMap().apply {
        putDouble("timestampMs", entry.timestampMs.toDouble())
        putString("modelName", entry.modelName)
        putString("engine", entry.engine)
        putDouble("bytes", entry.bytes.toDouble())
        putDouble("durationMs", entry.durationMs.toDouble())
        putDouble("averageBytesPerSecond", entry.averageBytesPerSecond)
        putString("outcome", entry.outcome)
        if (entry.errorMessage != null) putString("errorMessage", entry.errorMessage)
      })
    }
    promise.resolve(history)
  }

  @ReactMethod
  fun clearDownloadHistory(promise: Promise) {
    downloadPreferences.clearHistory()
    promise.resolve(null)
  }

  @ReactMethod
  fun startModelDownload() {
    if (downloadPaused) {
      emitError(null, "يوجد تنزيل موقوف مؤقتًا. استخدم زر الاستئناف لإكماله من آخر بايت محفوظ.")
      return
    }
    if (!downloadRunning.compareAndSet(false, true)) return
    activeGgufDownload = null
    downloadPauseController.resume()
    beginDownloadSession("mnn", "Qwen2.5 0.5B — MNN Q4", downloader.completedBytes(), MnnModelRepository.totalBytes)
    ioExecutor.execute {
      try {
        downloader.downloadAll()
        finishDownloadSession("completed")
        notifyDownloadCompleted()
        emit("MnnLocalAiDownloadCompleted", Arguments.createMap())
      } catch (_: DownloadPausedException) {
        markDownloadPaused()
      } catch (error: Exception) {
        finishDownloadSession("failed", error.message)
        emitError(null, error.message ?: "تعذر تنزيل النموذج")
      } finally {
        downloadRunning.set(false)
      }
    }
  }

  @ReactMethod
  fun getRecommendedGgufModels(promise: Promise) {
    val models = Arguments.createArray()
    RecommendedGgufCatalog.models.forEach { model ->
      models.pushMap(Arguments.createMap().apply {
        putString("id", model.id)
        putString("displayName", model.displayName)
        putString("description", model.description)
        putString("format", "GGUF · Q4_K_M")
        putDouble("bytes", model.bytes.toDouble())
        putDouble("recommendedRamGb", model.recommendedRamGb.toDouble())
      })
    }
    promise.resolve(models)
  }

  @ReactMethod
  fun startRecommendedGgufDownload(modelId: String) {
    val model = RecommendedGgufCatalog.byId(modelId)
    if (model == null) {
      emitError(null, "لا يوجد نموذج GGUF مطابق في الكتالوج")
      return
    }
    if (downloadPaused) {
      emitError(null, "يوجد تنزيل موقوف مؤقتًا. استأنفه أو أكمله قبل بدء تنزيل آخر.")
      return
    }
    if (!downloadRunning.compareAndSet(false, true)) return
    activeGgufDownload = model
    downloadPauseController.resume()
    beginDownloadSession("gguf", model.displayName, ggufStore.catalogPartialBytes(model), model.bytes)
    ioExecutor.execute {
      try {
        if (nativeLibraryLoaded) nativeRelease()
        ggufStore.downloadRecommended(model, ::emitDownloadProgress, downloadPauseController, downloadRateLimiter)
        finishDownloadSession("completed")
        notifyDownloadCompleted()
        emit("MnnLocalAiDownloadCompleted", Arguments.createMap())
      } catch (_: DownloadPausedException) {
        markDownloadPaused()
      } catch (error: Exception) {
        activeGgufDownload = null
        finishDownloadSession("failed", error.message)
        emitError(null, error.message ?: "تعذر تنزيل نموذج GGUF")
      } finally {
        if (!downloadPaused) activeGgufDownload = null
        downloadRunning.set(false)
      }
    }
  }

  @ReactMethod
  fun pauseModelDownload() {
    if (!downloadRunning.get() || latestDownloadPhase != "downloading") {
      emitError(null, "يمكن إيقاف نقل الملف مؤقتًا فقط أثناء التنزيل، وليس أثناء التحقق.")
      return
    }
    downloadPauseController.pause()
  }

  @ReactMethod
  fun resumeModelDownload() {
    if (!downloadPaused || !downloadRunning.compareAndSet(false, true)) return
    downloadPaused = false
    downloadPauseController.resume()
    val catalogModel = activeGgufDownload
    beginDownloadSession(if (catalogModel != null) "gguf" else "mnn", catalogModel?.displayName ?: "Qwen2.5 0.5B — MNN Q4", if (catalogModel != null) ggufStore.catalogPartialBytes(catalogModel) else downloader.completedBytes(), catalogModel?.bytes ?: MnnModelRepository.totalBytes)
    ioExecutor.execute {
      try {
        if (catalogModel != null) {
          if (nativeLibraryLoaded) nativeRelease()
          ggufStore.downloadRecommended(catalogModel, ::emitDownloadProgress, downloadPauseController, downloadRateLimiter)
        } else {
          downloader.downloadAll()
        }
        finishDownloadSession("completed")
        notifyDownloadCompleted()
        emit("MnnLocalAiDownloadCompleted", Arguments.createMap())
      } catch (_: DownloadPausedException) {
        markDownloadPaused()
      } catch (error: Exception) {
        activeGgufDownload = null
        finishDownloadSession("failed", error.message)
        emitError(null, error.message ?: "تعذر استئناف التنزيل")
      } finally {
        if (!downloadPaused) activeGgufDownload = null
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
      val gguf = ggufStore.selected()
      if (gguf == null && !downloader.isComplete()) {
        promise.reject("MODEL_NOT_READY", "لا توجد نسخة مكتملة ومتحقق منها من النموذج المحلي")
        return@execute
      }
      val started = System.nanoTime()
      val loaded = if (gguf != null) nativeLoadGguf(gguf.file.absolutePath) else nativeLoad(File(modelDirectory, "config.json").absolutePath)
      if (!loaded) {
        promise.reject("MODEL_LOAD_FAILED", if (gguf != null) "تعذر تحميل نموذج GGUF من التخزين المحلي" else "تعذر تحميل نموذج MNN من التخزين المحلي")
        return@execute
      }
      val loadMs = (System.nanoTime() - started) / 1_000_000
      val warmupStarted = System.nanoTime()
      val warmed = if (gguf != null) nativeWarmupGguf() else nativeWarmup()
      if (!warmed) {
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
    promise.resolve(if (ggufStore.selected() != null) nativeGenerateGguf(prompt, runId) else nativeGenerate(prompt, runId))
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
    if (downloadRunning.get() || downloadPaused) {
      emitError(null, "لا يمكن حذف النموذج أثناء التنزيل")
      return
    }
    ioExecutor.execute {
      if (nativeLibraryLoaded) nativeRelease()
      if (ggufStore.selected() != null) ggufStore.clearSelected() else downloader.deleteAll()
      emit("MnnLocalAiModelDeleted", Arguments.createMap())
    }
  }

  @ReactMethod
  fun importGguf(uri: String, displayName: String, expectedBytes: Double, promise: Promise) {
    ioExecutor.execute {
      try {
        if (nativeLibraryLoaded) nativeRelease()
        val imported = ggufStore.importFromUri(uri, displayName, expectedBytes.toLong())
        promise.resolve(Arguments.createMap().apply {
          putString("id", imported.id)
          putString("name", imported.name)
          putDouble("bytes", imported.bytes.toDouble())
          putString("format", "GGUF v${imported.version}")
        })
      } catch (error: Exception) {
        promise.reject("GGUF_IMPORT_FAILED", error.message, error)
      }
    }
  }

  @ReactMethod
  fun selectMnnModel(promise: Promise) {
    ioExecutor.execute {
      if (nativeLibraryLoaded) nativeRelease()
      ggufStore.clearSelected()
      promise.resolve(null)
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
    latestDownloadPhase = phase
    latestDownloadFile = currentFile
    updateDownloadTelemetry(downloadedBytes, totalBytes, phase)
    emit("MnnLocalAiDownloadProgress", Arguments.createMap().apply {
      putDouble("downloadedBytes", downloadedBytes.toDouble())
      putDouble("totalBytes", totalBytes.toDouble())
      putString("currentFile", currentFile)
      putString("phase", phase)
      putDouble("speedBytesPerSecond", latestDownloadSpeedBytesPerSecond)
      putDouble("etaSeconds", latestDownloadEtaSeconds.toDouble())
    })
  }

  private fun resetDownloadTelemetry(initialBytes: Long) {
    downloadPaused = false
    latestDownloadPhase = "downloading"
    latestDownloadFile = ""
    latestDownloadSpeedBytesPerSecond = 0.0
    latestDownloadEtaSeconds = -1L
    downloadSampleAtMs = SystemClock.elapsedRealtime()
    downloadSampleBytes = initialBytes
  }

  private fun beginDownloadSession(engine: String, modelName: String, initialBytes: Long, totalBytes: Long) {
    downloadStartedAtMs = SystemClock.elapsedRealtime()
    downloadStartedBytes = initialBytes
    downloadTotalBytes = totalBytes
    downloadModelName = modelName
    downloadEngine = engine
    configureDownloadRateLimit()
    resetDownloadTelemetry(initialBytes)
  }

  private fun finishDownloadSession(outcome: String, errorMessage: String? = null) {
    if (downloadStartedAtMs <= 0L) return
    val catalogModel = activeGgufDownload
    val finishedBytes = if (catalogModel != null) ggufStore.catalogPartialBytes(catalogModel) else downloader.completedBytes()
    val durationMs = (SystemClock.elapsedRealtime() - downloadStartedAtMs).coerceAtLeast(1L)
    val transferredBytes = (finishedBytes - downloadStartedBytes).coerceAtLeast(0L)
    downloadPreferences.add(DownloadHistoryEntry(
      timestampMs = System.currentTimeMillis(),
      modelName = downloadModelName,
      engine = downloadEngine,
      bytes = finishedBytes.coerceAtMost(downloadTotalBytes),
      durationMs = durationMs,
      averageBytesPerSecond = transferredBytes * 1000.0 / durationMs,
      outcome = outcome,
      errorMessage = errorMessage,
    ))
    downloadStartedAtMs = 0L
  }

  private fun configureDownloadRateLimit() {
    val settings = downloadPreferences.settings()
    val limit = if (settings.cellularSpeedLimitKbps > 0 && isCellularNetwork()) settings.cellularSpeedLimitKbps.toLong() * 1024L else 0L
    downloadRateLimiter.configure(limit)
  }

  private fun isCellularNetwork(): Boolean {
    val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
  }

  private fun downloadSettingsMap() = Arguments.createMap().apply {
    val settings = downloadPreferences.settings()
    putBoolean("completionNotificationsEnabled", settings.completionNotificationsEnabled)
    putDouble("cellularSpeedLimitKbps", settings.cellularSpeedLimitKbps.toDouble())
    putBoolean("isCellularNetwork", isCellularNetwork())
  }

  private fun notifyDownloadCompleted() {
    if (!downloadPreferences.settings().completionNotificationsEnabled) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val channelId = "model-downloads"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(NotificationChannel(channelId, "تنزيلات النماذج", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val notification = android.app.Notification.Builder(appContext, channelId)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("اكتمل تنزيل النموذج")
      .setContentText("${downloadModelName} جاهز للاستخدام محليًا.")
      .setAutoCancel(true)
      .build()
    manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
  }

  private fun updateDownloadTelemetry(downloadedBytes: Long, totalBytes: Long, phase: String) {
    if (phase != "downloading") {
      latestDownloadSpeedBytesPerSecond = 0.0
      latestDownloadEtaSeconds = -1L
      return
    }
    val now = SystemClock.elapsedRealtime()
    val elapsedMs = now - downloadSampleAtMs
    if (elapsedMs < 750L || downloadedBytes < downloadSampleBytes) return
    latestDownloadSpeedBytesPerSecond = (downloadedBytes - downloadSampleBytes) * 1000.0 / elapsedMs
    latestDownloadEtaSeconds = if (latestDownloadSpeedBytesPerSecond > 0.0) ((totalBytes - downloadedBytes).coerceAtLeast(0) / latestDownloadSpeedBytesPerSecond).toLong() else -1L
    downloadSampleAtMs = now
    downloadSampleBytes = downloadedBytes
  }

  private fun markDownloadPaused() {
    downloadPaused = true
    latestDownloadPhase = "paused"
    latestDownloadSpeedBytesPerSecond = 0.0
    latestDownloadEtaSeconds = -1L
    val catalogModel = activeGgufDownload
    val bytes = if (catalogModel != null) ggufStore.catalogPartialBytes(catalogModel) else downloader.completedBytes()
    val total = catalogModel?.bytes ?: MnnModelRepository.totalBytes
    finishDownloadSession("paused")
    emitDownloadProgress(bytes, total, latestDownloadFile, "paused")
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
  private external fun nativeLoadGguf(path: String): Boolean
  private external fun nativeWarmupGguf(): Boolean
  private external fun nativeGenerateGguf(prompt: String, runId: String): Boolean
  private external fun nativeStopGeneration()
  private external fun nativeRelease()

  private companion object {
    @Volatile private var nativeLibraryLoaded = false
    @Volatile private var nativeLoadError: String? = null
  }
}
