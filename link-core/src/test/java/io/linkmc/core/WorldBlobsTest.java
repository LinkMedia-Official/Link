package io.linkmc.core;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldBlobsTest {
    @org.junit.jupiter.api.Test void persistedDigestComparisonDoesNotDownloadAgain()throws Exception{
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var blobs=new WorldBlobs(new WorldBlobs.Store(){
            public WorldBlobs.Revision put(byte[] data){return new WorldBlobs.Revision("worlds","chunk","version");}
            public byte[] get(WorldBlobs.Revision revision){reads.incrementAndGet();return new byte[]{1,2};}
        });
        byte[] reference=blobs.encode("chunk",new byte[]{1,2});
        org.junit.jupiter.api.Assertions.assertTrue(blobs.matches(reference,new byte[]{1,2}));
        org.junit.jupiter.api.Assertions.assertFalse(blobs.matches(reference,new byte[]{1,3}));
        org.junit.jupiter.api.Assertions.assertEquals(0,reads.get());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1,2},blobs.decode(reference));
        org.junit.jupiter.api.Assertions.assertEquals(1,reads.get());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,()->blobs.matches(java.util.Arrays.copyOf(reference,reference.length-1),new byte[]{1,2}));
    }

    @Test void independentChunksUploadConcurrently()throws Exception{
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var uploads=new java.util.concurrent.atomic.AtomicInteger();
        var blobs=new WorldBlobs(new WorldBlobs.Store(){
            public WorldBlobs.Revision put(byte[] bytes)throws Exception{uploads.incrementAndGet();barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);return new WorldBlobs.Revision("b",UUID.randomUUID().toString(),"1");}
            public byte[] get(WorldBlobs.Revision r){throw new UnsupportedOperationException();}
        });
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->blobs.encode("a",new byte[]{1}));var b=pool.submit(()->blobs.encode("b",new byte[]{2}));
            byte[] ref=a.get(6,java.util.concurrent.TimeUnit.SECONDS);b.get(6,java.util.concurrent.TimeUnit.SECONDS);
            assertArrayEquals(ref,blobs.encode("a",new byte[]{1}));assertEquals(2,uploads.get());
        }
    }
    static final class Memory implements WorldBlobs.Store {
        final Map<WorldBlobs.Revision,byte[]> values=new HashMap<>();
        boolean unavailable;
        public WorldBlobs.Revision put(byte[] bytes)throws IOException{
            if(unavailable)throw new IOException("unavailable");
            var revision=new WorldBlobs.Revision("worlds","object.slime",UUID.randomUUID().toString());
            values.put(revision,bytes.clone());return revision;
        }
        public byte[] get(WorldBlobs.Revision revision)throws IOException{
            if(unavailable)throw new IOException("unavailable");
            return values.get(revision);
        }
    }
    @Test void exactRevisionSurvivesLaterWritesAndNewReader()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);
        byte[] first=blobs.encode("world",new byte[]{1});
        assertArrayEquals(first,blobs.encode("world",new byte[]{1}));assertEquals(1,store.values.size());
        byte[] second=blobs.encode("world",new byte[]{2});
        var restarted=new WorldBlobs(store);
        assertArrayEquals(new byte[]{1},restarted.decode(first));assertArrayEquals(new byte[]{2},restarted.decode(second));
    }
    @Test void uploadFailureDoesNotCacheUncommittedReference()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);store.unavailable=true;
        assertThrows(IOException.class,()->blobs.encode("world",new byte[]{1}));assertTrue(store.values.isEmpty());
        store.unavailable=false;assertArrayEquals(new byte[]{1},blobs.decode(blobs.encode("world",new byte[]{1})));
    }
    @Test void corruptionAndMissingStoreFailClosed()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);byte[] reference=blobs.encode("world",new byte[]{1});
        store.values.replaceAll((key,value)->new byte[]{9});
        assertThrows(IOException.class,()->blobs.decode(reference));
        assertThrows(IllegalStateException.class,()->new WorldBlobs(null).decode(reference));
    }
    @Test void existingSqlPayloadsRemainReadable()throws Exception{
        byte[] payload={1,2,3};assertArrayEquals(payload,new WorldBlobs(new Memory()).decode(payload));
        assertArrayEquals(payload,new WorldBlobs(null).encode("world",payload));
    }
}
