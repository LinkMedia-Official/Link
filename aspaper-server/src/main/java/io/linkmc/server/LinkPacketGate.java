package io.linkmc.server;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.util.*;
import java.util.concurrent.*;

/** Holds already encoded bytes, not mutable Packet/ItemStack objects. */
public final class LinkPacketGate extends ChannelOutboundHandlerAdapter {
    private final boolean enabled;
    // Autosave mode publishes gameplay immediately. Explicit API save futures acknowledge SQL durability.
    public LinkPacketGate(){this(false);}
    LinkPacketGate(boolean enabled){this.enabled=enabled;}
    private record Write(ChannelHandlerContext context,Object message,ChannelPromise promise) {}
    private static final Set<ChannelHandlerContext> CONTEXTS=ConcurrentHashMap.newKeySet();
    private static final Object LOCK=new Object();
    private static List<Write> pending;
    private static long pendingBytes;
    private static Throwable failure;
    private static final long LIMIT=64L*1024*1024;
    @Override public void handlerAdded(ChannelHandlerContext ctx){if(enabled)CONTEXTS.add(ctx);}
    @Override public void handlerRemoved(ChannelHandlerContext ctx){CONTEXTS.remove(ctx);}
    @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise){
        // Protocol configuration tasks must complete synchronously on the event loop.
        // They change handlers, carry no client-visible state, and are not journal output.
        if(!enabled || !(message instanceof io.netty.buffer.ByteBuf)){ctx.write(message,promise);return;}
        synchronized(LOCK){
            if(pending!=null){
                long bytes=message instanceof io.netty.buffer.ByteBuf b?b.readableBytes():1;
                if(pendingBytes+bytes>LIMIT){
                    failure=new IllegalStateException("Link outbound frame exceeds 64 MiB");
                    ReferenceCountUtil.release(message);promise.tryFailure(failure);return;
                }
                pendingBytes+=bytes;pending.add(new Write(ctx,message,promise));return;
            }
            // Enqueue under the same lock as publish: a concurrently arriving write
            // must never overtake the preceding committed batch on this event loop.
            try{ctx.executor().execute(()->ctx.write(message,promise));}
            catch(RejectedExecutionException e){ReferenceCountUtil.release(message);promise.tryFailure(e);}
        }
    }
    @Override public void flush(ChannelHandlerContext ctx){if(!enabled){ctx.flush();return;}synchronized(LOCK){if(pending!=null)return;ctx.executor().execute(ctx::flush);}}
    public static void begin(){synchronized(LOCK){if(pending!=null)throw new IllegalStateException("Nested packet frame");pending=new ArrayList<>();pendingBytes=0;failure=null;}}
    /** Drain queued encoders before capturing/committing; encoded messages can no longer alias live items. */
    public static void awaitEncoders()throws Exception{
        List<CompletableFuture<Void>> barriers=new ArrayList<>();
        for(var ctx:CONTEXTS){
            var barrier=new CompletableFuture<Void>();barriers.add(barrier);
            ctx.executor().execute(()->barrier.complete(null));
        }
        CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
        synchronized(LOCK){if(failure!=null)throw new IllegalStateException("Packet frame failed",failure);}
    }
    public static void publish(){
        synchronized(LOCK){
            if(pending==null)return;
            Map<ChannelHandlerContext,List<Write>> grouped=new LinkedHashMap<>();
            for(Write write:pending)grouped.computeIfAbsent(write.context(),k->new ArrayList<>()).add(write);
            grouped.forEach((ctx,batch)->{
                try{ctx.executor().execute(()->{for(Write w:batch)ctx.write(w.message(),w.promise());ctx.flush();});}
                catch(RejectedExecutionException e){for(Write w:batch){ReferenceCountUtil.release(w.message());w.promise().tryFailure(e);}ctx.close();}
            });
            pending=null;pendingBytes=0;
        }
    }
    public static void abort(Throwable reason){
        List<Write> writes;
        synchronized(LOCK){writes=pending;pending=null;pendingBytes=0;failure=reason;}
        if(writes!=null)for(Write w:writes){ReferenceCountUtil.release(w.message());w.promise().tryFailure(reason);w.context().close();}
        for(var ctx:CONTEXTS)ctx.close();
    }
}
