package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameCommitterTest {
    @Test void readsAndUnrelatedRestoreDoNotWaitForDatabaseCommit()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var journal=new FrameCommitter(tx->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return new Commit(tx.operation(),false);});
        journal.restore(new State("a",1,1,new byte[]{1}));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var committing=pool.submit(()->{journal.commit(List.of(),Map.of("a",new byte[]{2}),()->{});return null;});
            try{
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var reading=pool.submit(()->{journal.restore(new State("b",1,1,new byte[]{3}));return journal.state("a").orElseThrow().version();});
                assertEquals(1L,reading.get(1,java.util.concurrent.TimeUnit.SECONDS));
            }finally{release.countDown();}
            committing.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(2,journal.state("a").orElseThrow().version());
            journal.restore(new State("a",1,1,new byte[]{0}));assertEquals(2,journal.state("a").orElseThrow().version());
        }
    }
    @Test void playerAndBlockAndEntityPublishOnlyAfterOneCommit()throws Exception{
        List<String> sequence=new ArrayList<>();
        FrameCommitter journal=new FrameCommitter(tx->{assertEquals(3,tx.changes().size());sequence.add("db");return new Commit(tx.operation(),false);});
        journal.commit(List.of(),Map.of("player:test",new byte[]{1},"world:block",new byte[]{2},"world:entity",new byte[]{3}),()->sequence.add("packets"));
        assertEquals(List.of("db","packets"),sequence);
    }
    @Test void uncertainCommitReusesExactTransaction()throws Exception{
        AtomicReference<Transaction> first=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        FrameCommitter journal=new FrameCommitter(tx->{if(calls.getAndIncrement()==0){first.set(tx);throw new SQLException("lost response");}assertSame(first.get(),tx);return new Commit(tx.operation(),true);});
        AtomicBoolean sent=new AtomicBoolean();journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->sent.set(true));
        assertTrue(sent.get());assertEquals(1,journal.state("test:a").orElseThrow().version());
    }
    @Test void storageOutageDiscardsPublicationAndReplaysSameOperationLater()throws Exception{
        AtomicBoolean offline=new AtomicBoolean(true);List<Transaction> seen=new ArrayList<>();
        FrameCommitter journal=new FrameCommitter(tx->{seen.add(tx);if(offline.get())throw new SQLException("offline");return new Commit(tx.operation(),false);});
        AtomicBoolean sent=new AtomicBoolean();
        assertThrows(SQLException.class,()->journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->sent.set(true)));
        assertFalse(sent.get());assertFalse(journal.failed());assertTrue(journal.pendingRetry());assertTrue(journal.state("test:a").isEmpty());
        UUID unresolved=seen.get(0).operation();
        offline.set(false);seen.clear();
        journal.commit(List.of(),Map.of("test:a",new byte[]{1},"test:b",new byte[]{2}),()->sent.set(true));
        assertTrue(sent.get());assertFalse(journal.pendingRetry());
        assertEquals(unresolved,seen.get(0).operation(),"the unknown frame is replayed first with the same operation ID");
        assertEquals(List.of("test:b"),seen.get(1).changes().stream().map(Change::id).toList(),"replayed state is not written twice");
        assertEquals(1,journal.state("test:a").orElseThrow().version());assertEquals(1,journal.state("test:b").orElseThrow().version());
    }
    @Test void ownershipConflictPermanentlyFencesEngine(){
        FrameCommitter journal=new FrameCommitter(tx->{throw new ConflictException("Backend instance was replaced or its lease expired");});
        AtomicBoolean sent=new AtomicBoolean();
        assertThrows(ConflictException.class,()->journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->sent.set(true)));
        assertFalse(sent.get());assertTrue(journal.failed());assertTrue(journal.state("test:a").isEmpty());
        assertThrows(IllegalStateException.class,()->journal.commit(List.of(),Map.of(),()->sent.set(true)));
    }
    @Test void unchangedSnapshotsDoNotCreateNewEvents()throws Exception{
        AtomicInteger writes=new AtomicInteger();FrameCommitter journal=new FrameCommitter(tx->{writes.incrementAndGet();return new Commit(tx.operation(),false);});
        journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->{});
        journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->{});
        assertEquals(1,writes.get());
    }
}
