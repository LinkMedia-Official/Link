package io.linkmc.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Splits large native world snapshots into bounded aggregates in the SAME SQL transaction. */
public final class BlobSnapshots {
    private static final int PART=8*1024*1024;
    private BlobSnapshots(){}
    public static void put(Map<String,byte[]> states,String key,byte[] bytes)throws Exception{
        if(bytes.length>512*1024*1024)throw new IllegalArgumentException("Snapshot exceeds 512 MiB");
        int parts=(bytes.length+PART-1)/PART;
        var manifest=ByteBuffer.allocate(40).putInt(bytes.length).putInt(parts).put(MessageDigest.getInstance("SHA-256").digest(bytes));
        states.put(key+"/manifest",manifest.array());
        for(int i=0;i<parts;i++)states.put(key+"/"+i,Arrays.copyOfRange(bytes,i*PART,Math.min(bytes.length,(i+1)*PART)));
    }
    public static Optional<byte[]> get(Map<String,byte[]> states,String key)throws Exception{
        byte[] data=states.get(key+"/manifest");if(data==null||data.length==0)return Optional.empty();
        if(data.length!=40)throw new IllegalStateException("Invalid snapshot manifest");
        var manifest=ByteBuffer.wrap(data);int length=manifest.getInt(),parts=manifest.getInt();
        if(length<0 || length>512*1024*1024 || parts!=(length+PART-1)/PART)throw new IllegalStateException("Invalid snapshot size");
        byte[] digest=new byte[32];manifest.get(digest);byte[] result=new byte[length];
        for(int i=0;i<parts;i++){
            byte[] part=states.get(key+"/"+i);int expected=Math.min(PART,length-i*PART);
            if(part==null||part.length!=expected)throw new IllegalStateException("Missing snapshot part");
            System.arraycopy(part,0,result,i*PART,part.length);
        }
        if(!MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(result)))throw new IllegalStateException("Snapshot checksum mismatch");
        return Optional.of(result);
    }
}
