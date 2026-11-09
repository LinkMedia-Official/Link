package io.linkmc.server;

import com.infernalsuite.asp.api.world.*;
import com.infernalsuite.asp.api.world.properties.*;
import com.infernalsuite.asp.skeleton.*;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry;
import com.infernalsuite.asp.util.Util;
import io.linkmc.core.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkChunkSnapshotsTestSuite {
    @Test void detachedCaptureSurvivesLaterWorldMutationAndParallelEncoding()throws Exception{
        var live=world();var frozen=LinkChunkSnapshots.freeze(live);
        live.getChunk(0,-2).getExtraData().clear();live.getPropertyMap().setValue(SlimeProperties.SPAWN_Y,99);live.getChunkStorage().clear();
        Map<String,byte[]> states=new HashMap<>();var blobs=new WorldBlobs(null);
        try(var pipeline=new AsyncSavePipeline(2)){
            pipeline.submit(()->{new LinkChunkSnapshots().capture(frozen,KEY,blobs,Map.of(),states,pipeline);return null;}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        var saved=restore(states,blobs);assertEquals(2,saved.getChunkStorage().size());
        assertEquals("first",((CompoundBinaryTag)saved.getChunk(0,-2).getExtraData().get("ChunkBukkitValues")).getString("test:value"));
        assertNotEquals(99,saved.getPropertyMap().getValue(SlimeProperties.SPAWN_Y));
    }
    static final String KEY="native:test/world/mine";
    private SlimeChunk chunk(int x,String pdc){
        return new SlimeChunkSkeleton(x,-2,new SlimeChunkSection[0],null,List.of(),List.of(),
                new HashMap<>(Map.of("ChunkBukkitValues",CompoundBinaryTag.builder().putString("test:value",pdc).build())),null,null,null,null);
    }
    private SkeletonSlimeWorld world(){
        var chunks=new Long2ObjectOpenHashMap<SlimeChunk>();
        chunks.put(Util.chunkPosition(0,-2),chunk(0,"first"));chunks.put(Util.chunkPosition(1,-2),chunk(1,"second"));
        var props=new SlimePropertyMap();props.setValue(SlimeProperties.CHUNK_PRUNING,"aggressive");
        return new SkeletonSlimeWorld("mine",null,true,chunks,new ConcurrentHashMap<>(),props,5000);
    }
    private SlimeWorld restore(Map<String,byte[]> states,WorldBlobs blobs)throws Exception{
        return SlimeWorldReaderRegistry.readWorld(null,"mine",LinkChunkSnapshots.restore("mine",KEY,blobs,states).orElseThrow(),new SlimePropertyMap(),true);
    }
    @Test void changedChunkOnlyAndPdcOnlyAirChunkSurvives()throws Exception{
        var world=world();var storage=new LinkChunkSnapshots();var blobs=new WorldBlobs(null);Map<String,byte[]> states=new HashMap<>(),updates=new HashMap<>();
        storage.capture(world,KEY,blobs,states,updates);states.putAll(updates);updates.clear();
        var saved=restore(states,blobs);assertEquals(2,saved.getChunkStorage().size());
        assertEquals("first",((CompoundBinaryTag)saved.getChunk(0,-2).getExtraData().get("ChunkBukkitValues")).getString("test:value"));
        storage.capture(world,KEY,blobs,states,updates);assertEquals(Set.of(LinkChunkSnapshots.key(KEY)+"/index"),updates.keySet());
        updates.clear();world.getChunk(0,-2).getExtraData().put("ChunkBukkitValues",CompoundBinaryTag.builder().putString("test:value","changed").build());
        storage.capture(world,KEY,blobs,states,updates);states.putAll(updates);
        assertTrue(updates.keySet().stream().anyMatch(k->k.contains("/0_-2/")));
        assertFalse(updates.keySet().stream().anyMatch(k->k.contains("/1_-2/")));
        assertEquals("changed",((CompoundBinaryTag)restore(states,blobs).getChunk(0,-2).getExtraData().get("ChunkBukkitValues")).getString("test:value"));
        updates.clear();world.getChunkStorage().removeIf(c->c.getX()==0);
        storage.capture(world,KEY,blobs,states,updates);states.putAll(updates);assertEquals(1,restore(states,blobs).getChunkStorage().size());
        assertFalse(updates.keySet().stream().anyMatch(k->k.contains("/1_-2/")));
    }
    @Test void worldMetadataChangesDoNotRewriteChunkBodies()throws Exception{
        var world=world();var blobs=new WorldBlobs(null);var storage=new LinkChunkSnapshots();Map<String,byte[]> states=new HashMap<>(),updates=new HashMap<>();
        storage.capture(world,KEY,blobs,states,updates);states.putAll(updates);updates.clear();
        world.getPropertyMap().setValue(SlimeProperties.SPAWN_Y,90);
        storage.capture(world,KEY,blobs,states,updates);
        assertTrue(updates.keySet().stream().anyMatch(k->k.contains("/meta/")));
        assertFalse(updates.keySet().stream().anyMatch(k->k.contains("/0_-2/")||k.contains("/1_-2/")));
    }
    @Test void legacyMigrationIsAtomicAndCanRestartWithoutEventHistory()throws Exception{
        var world=world();var blobs=new WorldBlobs(null);Map<String,byte[]> states=new HashMap<>(),updates=new HashMap<>();
        byte[] legacy=SlimeSerializer.serialize(world,SlimeSerializer.captureChunks(world,world.getChunkStorage()));BlobSnapshots.put(states,KEY,legacy);
        var storage=new LinkChunkSnapshots();storage.capture(world,KEY,blobs,states,updates);
        assertArrayEquals(legacy,LinkChunkSnapshots.restore("mine",KEY,blobs,states).orElseThrow());
        states.putAll(updates);assertEquals(0,states.get(KEY+"/manifest").length);assertEquals(2,restore(states,blobs).getChunkStorage().size());
        updates.clear();new LinkChunkSnapshots().capture(world,KEY,blobs,states,updates);
        assertEquals(Set.of(LinkChunkSnapshots.key(KEY)+"/index"),updates.keySet());
    }
    @Test void missingChunkFailsClosedInsteadOfFallingBackToStaleLegacy()throws Exception{
        var world=world();var blobs=new WorldBlobs(null);Map<String,byte[]> states=new HashMap<>();
        new LinkChunkSnapshots().capture(world,KEY,blobs,Map.of(),states);
        states.remove(LinkChunkSnapshots.key(KEY)+"/0_-2/0");
        assertThrows(IllegalStateException.class,()->restore(states,blobs));
    }
    @Test void unchangedCaptureAndRestartDoNotUploadNewS3Objects()throws Exception{
        Map<String,byte[]> objects=new HashMap<>();
        var blobs=new WorldBlobs(new WorldBlobs.Store(){
            public WorldBlobs.Revision put(byte[] bytes){String id=UUID.randomUUID().toString();objects.put(id,bytes);return new WorldBlobs.Revision("bucket",id,"1");}
            public byte[] get(WorldBlobs.Revision revision){return objects.get(revision.key());}
        });
        Map<String,byte[]> states=new HashMap<>(),updates=new HashMap<>();var world=world();var storage=new LinkChunkSnapshots();
        storage.capture(world,KEY,blobs,states,updates);states.putAll(updates);assertEquals(3,objects.size());
        updates.clear();storage.capture(world,KEY,blobs,states,updates);assertEquals(3,objects.size());
        new LinkChunkSnapshots().capture(world,KEY,blobs,states,new HashMap<>());assertEquals(3,objects.size());
        assertEquals(2,restore(states,blobs).getChunkStorage().size());
    }
}
