package io.linkmc.core;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** SQL stores an immutable S3 revision reference only after upload confirmation. */
public final class WorldBlobs {
    private static final byte[] MAGIC="LINK-S3-REVISION-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    public record Revision(String bucket,String key,String version){}
    public interface Store {
        Revision put(byte[] world)throws Exception;
        byte[] get(Revision revision)throws Exception;
    }
    private record Cached(byte[] digest,byte[] reference){}
    private final Store store;
    private final Map<String,Cached> recent=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Object> locks=new java.util.concurrent.ConcurrentHashMap<>();
    public WorldBlobs(Store store){this.store=store;}
    public byte[] encode(String identity,byte[] world)throws Exception{
        if(store==null)return world;
        synchronized(locks.computeIfAbsent(identity,key->new Object())){
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(world);
        Cached cached=recent.get(identity);
        if(cached!=null&&MessageDigest.isEqual(digest,cached.digest()))return cached.reference().clone();
        Revision revision=store.put(world);
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.write(MAGIC);out.writeUTF(revision.bucket());out.writeUTF(revision.key());out.writeUTF(revision.version());out.write(digest);
        }
        byte[] reference=bytes.toByteArray();recent.put(identity,new Cached(digest,reference));return reference.clone();
        }
    }
    /** Compare the checksum embedded in a reference; do not download an already restored chunk again. */
    public boolean matches(byte[] value,byte[] world)throws Exception{
        if(value.length<MAGIC.length||!Arrays.equals(Arrays.copyOf(value,MAGIC.length),MAGIC))return Arrays.equals(value,world);
        if(store==null)throw new IllegalStateException("World references SlimeVault; enable the matching storage configuration");
        try(var input=new DataInputStream(new ByteArrayInputStream(value))){
            input.skipNBytes(MAGIC.length);input.readUTF();input.readUTF();input.readUTF();byte[] digest=input.readNBytes(32);
            if(digest.length!=32||input.read()!=-1)throw new IOException("Invalid world revision reference");
            return MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(world));
        }
    }
    public byte[] decode(byte[] value)throws Exception{
        if(value.length<MAGIC.length||!Arrays.equals(Arrays.copyOf(value,MAGIC.length),MAGIC))return value;
        if(store==null)throw new IllegalStateException("World references SlimeVault; enable the matching storage configuration");
        try(var input=new DataInputStream(new ByteArrayInputStream(value))){
            input.skipNBytes(MAGIC.length);
            var revision=new Revision(input.readUTF(),input.readUTF(),input.readUTF());
            byte[] digest=input.readNBytes(32);
            if(digest.length!=32||input.read()!=-1)throw new IOException("Invalid world revision reference");
            byte[] world=store.get(revision);
            if(!MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(world)))throw new IOException("SlimeVault world checksum mismatch");
            return world;
        }
    }
}
