package io.linkmc.core;

import java.util.*;
import java.util.concurrent.*;

/** One checkpoint at a time; bounded parallel preparation followed by one ordered SQL commit. */
public final class AsyncSavePipeline implements AutoCloseable {
    private final ExecutorService writer;
    private final ExecutorService workers;
    private final int parallelism;
    private CompletableFuture<?> pending=CompletableFuture.completedFuture(null);
    private boolean closed;
    public AsyncSavePipeline(int parallelism){
        if(parallelism<1||parallelism>16)throw new IllegalArgumentException("Save workers must be 1..16");
        this.parallelism=parallelism;
        writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Link-Save-Commit").factory());
        workers=new ThreadPoolExecutor(parallelism,parallelism,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(parallelism*2),
                Thread.ofPlatform().name("Link-Save-Worker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized <T> CompletableFuture<T> submit(Callable<T> work){
        if(closed||!pending.isDone())throw new RejectedExecutionException("A checkpoint is already running or storage is closed");
        if(pending.isCompletedExceptionally())throw new RejectedExecutionException("Previous checkpoint failed");
        var result=new CompletableFuture<T>();pending=result;
        writer.execute(()->{try{result.complete(work.call());}catch(Throwable failure){result.completeExceptionally(failure);}});
        return result;
    }
    /** Called by the single writer. Limits outstanding jobs; never uses the server thread as a worker. */
    public <T> List<T> parallel(List<? extends Callable<T>> jobs)throws Exception{return parallel(jobs,()->{});}
    public <T> List<T> parallel(List<? extends Callable<T>> jobs,Runnable progress)throws Exception{
        List<T> results=new ArrayList<>(jobs.size());
        var completion=new ExecutorCompletionService<T>(workers);
        Set<Future<T>> active=new HashSet<>();int next=0;
        try{
            while(next<jobs.size()||!active.isEmpty()){
                while(next<jobs.size()&&active.size()<parallelism)active.add(completion.submit(jobs.get(next++)));
                Future<T> done=completion.take();active.remove(done);results.add(done.get());progress.run();
            }
            return results;
        }finally{active.forEach(job->job.cancel(true));}
    }
    @Override public void close(){
        synchronized(this){closed=true;}
        writer.shutdown();
        try{
            boolean writerStopped=writer.awaitTermination(30,TimeUnit.SECONDS);
            workers.shutdown();
            if(!writerStopped||!workers.awaitTermination(30,TimeUnit.SECONDS)){
                writer.shutdownNow();workers.shutdownNow();throw new IllegalStateException("Storage workers did not stop");
            }
        }catch(InterruptedException e){writer.shutdownNow();workers.shutdownNow();Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
}
