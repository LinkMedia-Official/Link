package io.linkmc.core

import io.linkmc.api.LinkApi.{Change, Commit, Session, State, Transaction}

import java.sql.SQLException
import java.util.{Arrays, Optional, TreeMap, UUID}

/** One serialized native execution frame: snapshots become visible before packets do. */
final class FrameCommitter(writer: FrameCommitter.Writer) {
  private val committed: java.util.Map[String, State] = new java.util.concurrent.ConcurrentHashMap[String, State]()
  @volatile private var failedFlag: Boolean = false
  /** A transaction whose outcome is unknown. It is replayed unchanged (same operation ID) before any new frame. */
  private var unresolved: Transaction = null

  def restore(state: State): Unit = {
    if (failedFlag) throw new IllegalStateException("Frame journal is failed")
    committed.compute(state.id(), (key: String, previous: State) => if (previous == null || state.version() >= previous.version()) state else previous)
    ()
  }
  def state(id: String): Optional[State] = Optional.ofNullable(committed.get(id))
  /** All supplied bytes are copied by Change. Retries reuse the exact operation and payload. */
  /**
   * Storage outages (SQL/S3/timeouts) leave the frame unresolved and retryable: the next call replays the exact same
   * operation first, which the database deduplicates by operation ID. Only {@link ConflictException} (version conflict,
   * lost or replaced ownership) permanently fences the journal, because another writer may own the state.
   */
  @throws[Exception]
  def commit(owners: java.util.List[Session], snapshots: java.util.Map[String, Array[Byte]], publish: Runnable): Unit = synchronized {
    if (failedFlag) throw new IllegalStateException("Frame journal is failed")
    try {
      if (unresolved != null) { write(unresolved); applied(unresolved); unresolved = null }
      val changes = new java.util.ArrayList[Change]()
      val entries = new TreeMap[String, Array[Byte]](snapshots).entrySet().iterator()
      while (entries.hasNext()) {
        val entry = entries.next()
        val previous = committed.get(entry.getKey())
        if (previous == null || !Arrays.equals(previous.payload(), entry.getValue()))
          changes.add(new Change(entry.getKey(), if (previous == null) 0L else previous.version(), 1, "native:frame", entry.getValue()))
      }
      if (!changes.isEmpty()) {
        val transaction = new Transaction(UUID.randomUUID(), owners, changes)
        unresolved = transaction
        write(transaction)
        applied(transaction); unresolved = null
      }
      publish.run()
    } catch { case conflict: ConflictException => failedFlag = true; throw conflict }
  }
  @throws[Exception]
  private def write(transaction: Transaction): Unit =
    try { writer.commit(transaction); () }
    catch {
      case uncertain: SQLException =>
        // A lost COMMIT response is reconciled by replaying the same idempotent operation.
        try { writer.commit(transaction); () }
        catch { case retry: Exception => retry.addSuppressed(uncertain); throw retry }
    }
  private def applied(transaction: Transaction): Unit = {
    val changes = transaction.changes().iterator()
    while (changes.hasNext()) {
      val change = changes.next()
      restore(new State(change.id(), change.expectedVersion() + 1, change.schema(), change.after()))
    }
  }
  def failed(): Boolean = failedFlag
  /** True while a previous frame's outcome is unknown and will be replayed by the next commit. */
  def pendingRetry(): Boolean = synchronized { unresolved != null }
}

object FrameCommitter {
  @FunctionalInterface trait Writer {
    @throws[Exception]
    def commit(transaction: Transaction): Commit
  }
}
