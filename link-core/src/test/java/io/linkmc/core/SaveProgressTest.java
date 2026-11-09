package io.linkmc.core;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class SaveProgressTest {
 @Test void progressingSaveMayExceedOld120SecondLimit()throws Exception{
  var clock=new AtomicLong();var progress=new SaveProgress(clock::get);progress.begin();progress.phase("chunks",10);
  for(int i=0;i<10;i++){clock.addAndGet(TimeUnit.SECONDS.toNanos(30));progress.advance();progress.check(120,1800);}
  assertTrue(progress.status().contains("elapsed=300s"));
 }
 @Test void stalledWorkTimesOutWithPhaseAndCount(){
  var clock=new AtomicLong();var progress=new SaveProgress(clock::get);progress.begin();progress.phase("sql",100);progress.advance();
  clock.set(TimeUnit.SECONDS.toNanos(120));var error=assertThrows(TimeoutException.class,()->progress.check(120,1800));
  assertTrue(error.getMessage().contains("phase=sql"));assertTrue(error.getMessage().contains("completed=1/100"));
 }
 @Test void progressCannotExtendAbsoluteDeadline(){
  var clock=new AtomicLong();var progress=new SaveProgress(clock::get);progress.begin();clock.set(TimeUnit.SECONDS.toNanos(1800));progress.advance();
  assertThrows(TimeoutException.class,()->progress.check(120,1800));
 }
 @Test void waitingPropagatesStorageFailure(){
  var progress=new SaveProgress();progress.begin();var future=CompletableFuture.failedFuture(new IllegalStateException("storage down"));
  var error=assertThrows(ExecutionException.class,()->progress.await(future,120,1800,s->{}));assertEquals("storage down",error.getCause().getMessage());
 }
}
