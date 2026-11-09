package io.linkmc.core

import java.util.concurrent.{ArrayBlockingQueue, Callable, CompletableFuture, ExecutorCompletionService, ExecutorService, Executors, Future, RejectedExecutionException, ThreadPoolExecutor, TimeUnit}

/** One checkpoint at a time; bounded parallel preparation followed by one ordered SQL commit. */
final class AsyncSavePipeline(parallelism: Int) extends AutoCloseable {
  private var pending: CompletableFuture[?] = CompletableFuture.completedFuture(null)
  private var closed: Boolean = false
  if (parallelism < 1 || parallelism > 16) throw new IllegalArgumentException("Save workers must be 1..16")
  private val writer: ExecutorService = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Link-Save-Commit").factory())
  private val workers: ExecutorService = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](parallelism * 2),
    Thread.ofPlatform().name("Link-Save-Worker-", 0).factory(), new ThreadPoolExecutor.AbortPolicy())

  def submit[T](work: Callable[T]): CompletableFuture[T] = synchronized {
    if (closed || !pending.isDone()) throw new RejectedExecutionException("A checkpoint is already running or storage is closed")
    // A failed checkpoint is retryable: the caller re-captures memory state and FrameCommitter replays any
    // frame whose SQL outcome is unknown before writing anything newer.
    val result = new CompletableFuture[T](); pending = result
    writer.execute(() => { try result.complete(work.call()) catch { case failure: Throwable => result.completeExceptionally(failure) }; () })
    result
  }
  /** Called by the single writer. Limits outstanding jobs; never uses the server thread as a worker. */
  @throws[Exception]
  def parallel[T](jobs: java.util.List[? <: Callable[T]]): java.util.List[T] = parallel(jobs, () => ())
  @throws[Exception]
  def parallel[T](jobs: java.util.List[? <: Callable[T]], progress: Runnable): java.util.List[T] = {
    val results: java.util.List[T] = new java.util.ArrayList[T](jobs.size())
    val completion = new ExecutorCompletionService[T](workers)
    val active: java.util.Set[Future[T]] = new java.util.HashSet[Future[T]](); var next = 0
    try {
      while (next < jobs.size() || !active.isEmpty()) {
        while (next < jobs.size() && active.size() < parallelism) { val job: Callable[T] = jobs.get(next); next += 1; active.add(completion.submit(job)) }
        val done = completion.take(); active.remove(done); results.add(done.get()); progress.run()
      }
      results
    } finally active.forEach(job => { job.cancel(true); () })
  }
  override def close(): Unit = {
    this.synchronized { closed = true }
    writer.shutdown()
    try {
      val writerStopped = writer.awaitTermination(30, TimeUnit.SECONDS)
      workers.shutdown()
      if (!writerStopped || !workers.awaitTermination(30, TimeUnit.SECONDS)) {
        writer.shutdownNow(); workers.shutdownNow(); throw new IllegalStateException("Storage workers did not stop")
      }
    } catch { case e: InterruptedException => writer.shutdownNow(); workers.shutdownNow(); Thread.currentThread().interrupt(); throw new IllegalStateException(e) }
  }
}
