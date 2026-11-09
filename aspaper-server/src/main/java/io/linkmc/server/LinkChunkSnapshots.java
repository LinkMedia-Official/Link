package io.linkmc.server;

import com.infernalsuite.asp.Converter;
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry;
import com.infernalsuite.asp.api.world.*;
import com.infernalsuite.asp.api.world.properties.*;
import com.infernalsuite.asp.level.SlimeInMemoryWorld;
import com.infernalsuite.asp.level.chunk.*;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.util.Util;
import io.linkmc.core.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Content-detected chunk checkpoints. All parts and the index commit in the same native frame. */
final class LinkChunkSnapshots {
    private record Cached(byte[] digest) {}
    private final Map<String,Cached> captured=new ConcurrentHashMap<>();

    /** Main-thread capture only. Workers must never traverse live worlds or Bukkit objects. */
    static SlimeWorld freeze(SlimeWorld live){
        var metadata=shell(live,true);
        var chunks=new Long2ObjectOpenHashMap<SlimeChunk>();
        for(SlimeChunk source:live.getChunkStorage()){
            SlimeChunk copy;
            if(source instanceof NMSSlimeChunk nms){
                copy=PartiallySerializedSlimeChunk.of(nms,
                        metadata.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS),
                        metadata.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS),
                        metadata.getPropertyMap().getValue(SlimeProperties.SAVE_POI));
            }else{
                var sections=source.getSections();var cloned=new SlimeChunkSection[sections.length];
                for(int i=0;i<sections.length;i++){
                    var section=sections[i];
                    cloned[i]=new com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton(section.getBlockStatesTag(),section.getBiomeTag(),
                            section.getBlockLight()==null?null:section.getBlockLight().clone(),section.getSkyLight()==null?null:section.getSkyLight().clone());
                }
                copy=new com.infernalsuite.asp.skeleton.SlimeChunkSkeleton(source.getX(),source.getZ(),cloned,source.getHeightMaps(),
                        List.copyOf(source.getTileEntities()),List.copyOf(source.getEntities()),Map.copyOf(source.getExtraData()),source.getUpgradeData(),
                        source.getPoiChunkSections(),source.getBlockTicks(),source.getFluidTicks());
            }
            chunks.put(Util.chunkPosition(copy.getX(),copy.getZ()),copy);
        }
        return new SkeletonSlimeWorld(metadata.getName(),null,true,chunks,metadata.getExtraData(),metadata.getPropertyMap(),metadata.getDataVersion());
    }

    static String key(String worldKey) throws Exception {
        // Keep aggregate IDs bounded even for long world names.
        int end=worldKey.indexOf("/world/");
        return worldKey.substring(0,end)+"/chunks/"+HexFormat.of().formatHex(hash(worldKey.getBytes(StandardCharsets.UTF_8)));
    }
    private static byte[] hash(byte[] bytes)throws Exception{return MessageDigest.getInstance("SHA-256").digest(bytes);}
    private static SkeletonSlimeWorld shell(SlimeWorld world,boolean metadata){
        var properties=new SlimePropertyMap(new HashMap<>(world.getPropertyMap().getProperties()));
        if(!metadata){
            properties=new SlimePropertyMap();
            properties.setValue(SlimeProperties.SAVE_BLOCK_TICKS,world.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS));
            properties.setValue(SlimeProperties.SAVE_FLUID_TICKS,world.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS));
            properties.setValue(SlimeProperties.SAVE_POI,world.getPropertyMap().getValue(SlimeProperties.SAVE_POI));
        }
        var extra=new ConcurrentHashMap<String,net.kyori.adventure.nbt.BinaryTag>();
        if(metadata){
            extra.putAll(world.getExtraData());
            if(world instanceof SlimeInMemoryWorld live)
                extra.put("BukkitValues",Converter.convertTag(live.getInstance().persistentDataContainer.toTagCompound().copy()));
        }
        return new SkeletonSlimeWorld(world.getName(),null,true,new Long2ObjectOpenHashMap<>(),extra,properties,world.getDataVersion());
    }
    void capture(SlimeWorld world,String legacyKey,WorldBlobs blobs,Map<String,byte[]> previous,Map<String,byte[]> updates)throws Exception{
        capture(world,legacyKey,blobs,previous,updates,null);
    }
    void capture(SlimeWorld world,String legacyKey,WorldBlobs blobs,Map<String,byte[]> previous,Map<String,byte[]> updates,AsyncSavePipeline pipeline)throws Exception{
        capture(world,legacyKey,blobs,previous,updates,pipeline,null);
    }
    void capture(SlimeWorld world,String legacyKey,WorldBlobs blobs,Map<String,byte[]> previous,Map<String,byte[]> updates,AsyncSavePipeline pipeline,io.linkmc.core.SaveProgress progress)throws Exception{
        if(progress!=null)progress.phase("world-metadata:"+world.getName(),1);
        String root=key(legacyKey);
        var metadata=shell(world,true);
        byte[] metaBytes=SlimeSerializer.serialize(metadata,new byte[4]);
        byte[] metaHash=hash(metaBytes);
        Cached oldMeta=captured.get(root+"/meta");
        if(oldMeta==null||!MessageDigest.isEqual(oldMeta.digest(),metaHash)){
            put(root+"/meta",metaBytes,blobs,previous,updates,oldMeta==null);
            captured.put(root+"/meta",new Cached(metaHash));
        }
        List<String> coordinates=new ArrayList<>();
        var chunkWorld=shell(world,false);
        byte[] properties=SlimeSerializer.serialize(chunkWorld,new byte[4]);
        List<java.util.concurrent.Callable<Map<String,byte[]>>> jobs=new ArrayList<>();
        // Compare complete chunk content, not only vanilla's unsaved flag: PDC, entity movement,
        // lighting and pending ticks can change without that flag changing between frames.
        for(SlimeChunk source:world.getChunkStorage()){
            String coordinate=source.getX()+"_"+source.getZ();coordinates.add(coordinate);
            SlimeChunk chunk=source;
            if(source instanceof NMSSlimeChunk live)chunk=PartiallySerializedSlimeChunk.of(live,
                    metadata.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS),
                    metadata.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS),
                    metadata.getPropertyMap().getValue(SlimeProperties.SAVE_POI));
            SlimeChunk frozen=chunk;
            jobs.add(()->{
            Map<String,byte[]> changed=new HashMap<>();
            // Serializer updates its world metadata map, so each job owns its shell.
            var localWorld=shell(chunkWorld,false);
            byte[] raw=SlimeSerializer.captureChunks(localWorld,List.of(frozen));
            // Include format properties in the fingerprint; their changes affect the SRF header.
            var digest=MessageDigest.getInstance("SHA-256");digest.update(properties);digest.update(raw);
            String chunkKey=root+"/"+coordinate;
            byte[] fingerprint=digest.digest();Cached old=captured.get(chunkKey);
            if(old!=null&&MessageDigest.isEqual(old.digest(),fingerprint))return changed;
            put(chunkKey,SlimeSerializer.serialize(localWorld,raw),blobs,previous,changed,old==null);
            captured.put(chunkKey,new Cached(fingerprint));
            return changed;
            });
        }
        if(progress!=null)progress.phase("chunks:"+world.getName(),jobs.size());
        if(pipeline==null){for(var job:jobs)updates.putAll(job.call());}
        else for(var result:pipeline.parallel(jobs,()->{if(progress!=null)progress.advance();}))updates.putAll(result);
        Collections.sort(coordinates);
        updates.put(root+"/index",("1\n"+String.join("\n",coordinates)).getBytes(StandardCharsets.UTF_8));
        Set<String> present=new HashSet<>(coordinates);
        for(String coordinate:index(previous.get(root+"/index")))if(!present.contains(coordinate)){
            clear(root+"/"+coordinate,previous,updates);captured.remove(root+"/"+coordinate);
        }
        // The first incremental checkpoint atomically supersedes legacy SRF parts.
        clear(legacyKey,previous,updates);
    }
    private static void clear(String key,Map<String,byte[]> previous,Map<String,byte[]> updates){
        for(var entry:previous.entrySet())if(entry.getKey().startsWith(key+"/")&&entry.getValue().length>0)
            updates.put(entry.getKey(),new byte[0]);
    }
    private static void put(String key,byte[] raw,WorldBlobs blobs,Map<String,byte[]> previous,Map<String,byte[]> updates,boolean comparePersisted)throws Exception{
        // On restart, compare against the persisted object before allocating another S3 revision.
        if(comparePersisted){
            var old=BlobSnapshots.get(previous,key);
            if(old.isPresent()&&blobs.matches(old.get(),raw))return;
        }
        Map<String,byte[]> parts=new HashMap<>();BlobSnapshots.put(parts,key,blobs.encode(key,raw));
        for(var entry:previous.entrySet())if(entry.getKey().startsWith(key+"/")&&!parts.containsKey(entry.getKey())&&entry.getValue().length>0)
            updates.put(entry.getKey(),new byte[0]);
        updates.putAll(parts);
    }
    private static List<String> index(byte[] bytes){
        if(bytes==null)return List.of();
        String value=new String(bytes,StandardCharsets.UTF_8);
        if(!value.startsWith("1\n"))throw new IllegalStateException("Unknown chunk snapshot format");
        if(value.length()==2)return List.of();
        var result=List.of(value.substring(2).split("\n",-1));
        if(new HashSet<>(result).size()!=result.size()||result.stream().anyMatch(s->!s.matches("-?[0-9]+_-?[0-9]+")))
            throw new IllegalStateException("Invalid chunk snapshot index");
        return result;
    }
    static Optional<byte[]> restore(String name,String legacyKey,WorldBlobs blobs,Map<String,byte[]> states)throws Exception{
        String root=key(legacyKey);
        if(!states.containsKey(root+"/index")){
            var legacy=BlobSnapshots.get(states,legacyKey);
            return legacy.isEmpty()?Optional.empty():Optional.of(blobs.decode(legacy.get()));
        }
        // Data fixers run once when the assembled SRF is loaded by the normal world driver.
        var meta=SlimeWorldReaderRegistry.readWorld(null,name,read(root+"/meta",blobs,states),new SlimePropertyMap(),true);
        var chunks=new Long2ObjectOpenHashMap<SlimeChunk>();
        for(String coordinate:index(states.get(root+"/index"))){
            var part=SlimeWorldReaderRegistry.readWorld(null,name,read(root+"/"+coordinate,blobs,states),new SlimePropertyMap(),true);
            if(part.getChunkStorage().size()!=1)throw new IllegalStateException("Invalid chunk checkpoint");
            SlimeChunk chunk=part.getChunkStorage().iterator().next();
            if(!coordinate.equals(chunk.getX()+"_"+chunk.getZ()))throw new IllegalStateException("Chunk coordinate mismatch");
            chunks.put(Util.chunkPosition(chunk.getX(),chunk.getZ()),chunk);
        }
        var world=new SkeletonSlimeWorld(name,null,true,chunks,meta.getExtraData(),meta.getPropertyMap(),meta.getDataVersion());
        return Optional.of(SlimeSerializer.serialize(world,SlimeSerializer.captureChunks(world,world.getChunkStorage())));
    }
    private static byte[] read(String key,WorldBlobs blobs,Map<String,byte[]> states)throws Exception{
        return blobs.decode(BlobSnapshots.get(states,key).orElseThrow(()->new IllegalStateException("Missing chunk snapshot: "+key)));
    }
}
