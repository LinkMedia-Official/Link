package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One provisioner per backend; publishes readiness only after world creation succeeds. */
public final class SpawnProvisioner {
    private final Worlds worlds;
    private final NetworkStore network;
    private final String node,template;
    private final int version,capacity,maxWorlds;
    private final AtomicBoolean creating=new AtomicBoolean();
    public SpawnProvisioner(Worlds worlds,NetworkStore network,String node,String template,int version,int capacity,int maxWorlds){
        io.linkmc.api.LinkApi.requireName(node);io.linkmc.api.LinkApi.requireName(template);
        if(version<1||capacity<1||maxWorlds<1)throw new IllegalArgumentException("Invalid spawn limits");
        this.worlds=worlds;this.network=network;this.node=node;this.template=template;this.version=version;this.capacity=capacity;this.maxWorlds=maxWorlds;
    }
    /** Call on a background control executor, not a tick thread. No unbounded world creation. */
    public CompletionStage<Boolean> ensureSpare(double x,double y,double z,float yaw,float pitch){
        if(!creating.compareAndSet(false,true))return CompletableFuture.completedFuture(false);
        try {
            var local=network.spawnChannels().stream().filter(c -> c.node().equals(node)).toList();
            if(network.channelCount(node)>=maxWorlds || local.stream().anyMatch(c -> c.occupied()+c.reserved()==0)){
                creating.set(false);return CompletableFuture.completedFuture(false);
            }
            String name="spawn-"+UUID.randomUUID();
            return worlds.create(name,template,version,false).thenApplyAsync(info -> {
                try {network.register(node+":"+name,new Destination(node,name,x,y,z,yaw,pitch),capacity);return true;}
                catch(Exception e){worlds.unload(name);throw new CompletionException(e);}
            }).whenComplete((v,e) -> creating.set(false));
        } catch(Exception e){creating.set(false);return CompletableFuture.failedFuture(e);}
    }
}
