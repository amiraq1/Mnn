package com.app.mnnlocalai.mnn

import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

class DownloadPausedException : IOException("تم إيقاف التنزيل مؤقتًا")

class DownloadPauseController {
  private val paused = AtomicBoolean(false)
  @Volatile private var activeConnection: HttpURLConnection? = null

  fun pause() {
    paused.set(true)
    activeConnection?.disconnect()
  }

  fun resume() = paused.set(false)

  fun isPaused(): Boolean = paused.get()

  fun attach(connection: HttpURLConnection) {
    activeConnection = connection
    if (paused.get()) connection.disconnect()
  }

  fun detach(connection: HttpURLConnection) {
    if (activeConnection === connection) activeConnection = null
  }

  fun throwIfPaused() {
    if (paused.get()) throw DownloadPausedException()
  }
}
