package com.app.mnnlocalai.mnn

import android.os.SystemClock

class DownloadRateLimiter {
  @Volatile private var bytesPerSecondLimit = 0L
  private var startedAtMs = 0L
  private var bytesInWindow = 0L

  @Synchronized
  fun configure(bytesPerSecondLimit: Long) {
    this.bytesPerSecondLimit = bytesPerSecondLimit.coerceAtLeast(0)
    startedAtMs = SystemClock.elapsedRealtime()
    bytesInWindow = 0L
  }

  fun throttle(bytesJustTransferred: Int) {
    val limit = bytesPerSecondLimit
    if (limit <= 0 || bytesJustTransferred <= 0) return
    val waitMs = synchronized(this) {
      if (startedAtMs == 0L) startedAtMs = SystemClock.elapsedRealtime()
      bytesInWindow += bytesJustTransferred.toLong()
      val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(1L)
      val targetElapsedMs = bytesInWindow * 1000L / limit
      (targetElapsedMs - elapsedMs).coerceAtLeast(0L)
    }
    if (waitMs > 0) Thread.sleep(waitMs)
  }
}
