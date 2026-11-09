package io.linkmc.core;

import io.linkmc.api.LinkApi;
import java.security.MessageDigest;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Immutable published SRF blobs. Editing always happens in a separate world. */
public final class TemplateStore {
    private final Database db;
    public TemplateStore(Database db) { this.db=db; }
    public void publish(String name,int version,byte[] srf) throws Exception {publish(name,version,srf,LinkApi.ChannelPolicy.defaults());}
    public void publish(String name,int version,byte[] srf,LinkApi.ChannelPolicy policy) throws Exception {
        LinkApi.requireName(name);
        if(version<1 || srf.length==0) throw new IllegalArgumentException("Invalid template");
        byte[] bytes=srf.clone(), digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        byte[] stored=db.worldBlobs().encode("template:"+name+":"+version,bytes);
        db.transaction(c -> {
            lock(c,"template:"+name+":"+version);
            try(var s=sql(c,"SELECT sha256 FROM link_templates WHERE name=? AND version=?",name,version);var r=s.executeQuery()) {
                if(r.next()) {
                    if(!MessageDigest.isEqual(digest,r.getBytes(1))) throw new ConflictException("Published template versions are immutable");
                    if(!policy.equals(policy(c,name,version)))throw new ConflictException("Published channel policy is immutable");
                    return null;
                }
            }
            update(c,"INSERT INTO link_templates(name,version,payload,sha256) VALUES (?,?,?,?)",name,version,stored,digest);
            update(c,"INSERT INTO link_template_channels(name,version,capacity,max_channels,auto_expand,network_wide) VALUES (?,?,?,?,?,?)",name,version,policy.capacity(),policy.maxChannels(),policy.autoExpand(),policy.networkWide()); return null;
        });
    }
    static LinkApi.ChannelPolicy policy(java.sql.Connection c,String name,int version)throws Exception{
        try(var s=sql(c,"SELECT * FROM link_template_channels WHERE name=? AND version=?",name,version);var r=s.executeQuery()){
            return r.next()?new LinkApi.ChannelPolicy(r.getInt("capacity"),r.getInt("max_channels"),r.getBoolean("auto_expand"),r.getBoolean("network_wide")):LinkApi.ChannelPolicy.defaults();
        }
    }
    public byte[] read(String name,int version) throws Exception {
        return find(name,version).orElseThrow(() -> new ConflictException("Unknown template version"));
    }
    /** Only absence returns empty; database and checksum failures must still stop startup. */
    public Optional<byte[]> find(String name,int version) throws Exception {
        LinkApi.requireName(name);
        return db.transaction(c -> {
            try(var s=sql(c,"SELECT payload,sha256 FROM link_templates WHERE name=? AND version=?",name,version);var r=s.executeQuery()) {
                if(!r.next()) return Optional.empty();
                byte[] bytes=db.worldBlobs().decode(r.getBytes(1));
                if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),r.getBytes(2))) throw new ConflictException("Template checksum mismatch");
                return Optional.of(bytes);
            }
        });
    }
    public List<String> list() throws Exception {
        return db.transaction(c -> {
            var result=new ArrayList<String>();
            try(var s=sql(c,"SELECT name,version FROM link_templates ORDER BY name,version");var r=s.executeQuery()) {
                while(r.next()) result.add(r.getString(1)+":"+r.getInt(2));
            }
            return List.copyOf(result);
        });
    }
}
