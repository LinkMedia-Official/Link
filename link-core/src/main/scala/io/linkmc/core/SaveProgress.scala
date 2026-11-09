package io.linkmc.core

import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}
import java.util.function.LongSupplier

/** Monotonic progress deadlines shared by tick polling and startup/shutdown waits. */
final class SaveProgress private[core] (clock: LongSupplier) {
  private var started, lastProgress, lastReport, completed, total: Long = 0L
  private var currentPhase: String = "idle"

  def this() = this(() => System.nanoTime())

  def begin(): Unit = synchronized {
    val now = clock.getAsLong(); started = now; lastProgress = now; lastReport = now
    completed = 0; total = 0; currentPhase = "capture"
  }
  def phase(phase: String, total: Long): Unit = synchronized { currentPhase = phase; this.total = total; completed = 0; lastProgress = clock.getAsLong() }
  def advance(): Unit = synchronized { completed += 1; lastProgress = clock.getAsLong() }
  def status(): String = synchronized {
    val now = clock.getAsLong()
    "phase=" + currentPhase + ", completed=" + completed + "/" + total + ", elapsed=" + TimeUnit.NANOSECONDS.toSeconds(now - started) + "s, idle=" + TimeUnit.NANOSECONDS.toSeconds(now - lastProgress) + "s"
  }
  @throws[TimeoutException]
  def check(stallSeconds: Int, totalSeconds: Int): Unit = synchronized {
    val now = clock.getAsLong()
    if (now - lastProgress >= TimeUnit.SECONDS.toNanos(stallSeconds) || now - started >= TimeUnit.SECONDS.toNanos(totalSeconds)) throw new TimeoutException("Link save deadline exceeded: " + status())
  }
  def reportDue(): Boolean = synchronized {
    val now = clock.getAsLong()
    if (now - lastReport < TimeUnit.SECONDS.toNanos(10)) false
    else { lastReport = now; true }
  }
  @throws[Exception]
  def await[T](future: CompletableFuture[T], stallSeconds: Int, totalSeconds: Int, report: java.util.function.Consumer[String]): T = {
    while (!future.isDone()) {
      check(stallSeconds, totalSeconds); if (reportDue()) report.accept(status())
      try { return future.get(1, TimeUnit.SECONDS) } catch { case pending: TimeoutException => }
    }
    future.get()
  }
}
