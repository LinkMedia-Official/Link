package io.linkmc.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncSavePipelineTest {
    @Test void submissionDoesNotBlockCallerAndDoesNotQueueAnotherCheckpoint()throws Exception{
        try(var pipeline=new AsyncSavePipeline(2)){
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=pipeline.submit(()->{started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Thread.currentThread().getName();});
            try{
                assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(first.isDone());
                assertThrows(RejectedExecutionException.class,()->pipeline.submit(()->2));
            }finally{release.countDown();}
            assertEquals("Link-Save-Commit",first.get(5,TimeUnit.SECONDS));
            assertEquals(2,pipeline.submit(()->2).get(5,TimeUnit.SECONDS));
        }
    }
    @Test void parallelPreparationIsBoundedAndCommitFollowsEveryJob()throws Exception{
        try(var pipeline=new AsyncSavePipeline(3)){
            var active=new AtomicInteger();var peak=new AtomicInteger();var done=new AtomicInteger();var barrier=new CyclicBarrier(3);
            List<Callable<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<12;i++)jobs.add(()->{
                assertTrue(Thread.currentThread().getName().startsWith("Link-Save-Worker-"));
                int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);
                try{barrier.await(5,TimeUnit.SECONDS);return done.incrementAndGet();}finally{active.decrementAndGet();}
            });
            assertEquals(12,pipeline.submit(()->{var result=pipeline.parallel(jobs);assertEquals(12,done.get());return result.size();}).get(10,TimeUnit.SECONDS));
            assertEquals(3,peak.get());
        }
    }
    @Test void preparationFailureCannotCommitButLaterCheckpointMayRetry()throws Exception{
        try(var pipeline=new AsyncSavePipeline(2)){
            var committed=new AtomicBoolean();
            var result=pipeline.submit(()->{pipeline.parallel(List.of(()->{throw new IllegalStateException("upload failure");}));committed.set(true);return null;});
            assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));assertFalse(committed.get());
            assertEquals(7,pipeline.submit(()->7).get(5,TimeUnit.SECONDS));
        }
    }
}
