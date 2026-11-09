package io.linkmc.core;

import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Monotonic progress deadlines shared by tick polling and startup/shutdown waits. */
public final class SaveProgress {
    private final LongSupplier clock;
    private long started,lastProgress,lastReport,completed,total;
    private String phase="idle";
    public SaveProgress(){this(System::nanoTime);}
    SaveProgress(LongSupplier clock){this.clock=clock;}
    public synchronized void begin(){started=lastProgress=lastReport=clock.getAsLong();completed=total=0;phase="capture";}
    public synchronized void phase(String phase,long total){this.phase=phase;this.total=total;completed=0;lastProgress=clock.getAsLong();}
    public synchronized void advance(){completed++;lastProgress=clock.getAsLong();}
    public synchronized String status(){long now=clock.getAsLong();return "phase="+phase+", completed="+completed+"/"+total+", elapsed="+TimeUnit.NANOSECONDS.toSeconds(now-started)+"s, idle="+TimeUnit.NANOSECONDS.toSeconds(now-lastProgress)+"s";}
    public synchronized void check(int stallSeconds,int totalSeconds)throws TimeoutException{
        long now=clock.getAsLong();
        if(now-lastProgress>=TimeUnit.SECONDS.toNanos(stallSeconds)||now-started>=TimeUnit.SECONDS.toNanos(totalSeconds))throw new TimeoutException("Link save deadline exceeded: "+status());
    }
    public synchronized boolean reportDue(){long now=clock.getAsLong();if(now-lastReport<TimeUnit.SECONDS.toNanos(10))return false;lastReport=now;return true;}
    public <T>T await(CompletableFuture<T> future,int stallSeconds,int totalSeconds,java.util.function.Consumer<String> report)throws Exception{
        while(!future.isDone()){
            check(stallSeconds,totalSeconds);if(reportDue())report.accept(status());
            try{return future.get(1,TimeUnit.SECONDS);}catch(TimeoutException pending){}
        }
        return future.get();
    }
}
