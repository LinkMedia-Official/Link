package io.linkmc.core;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;

/** Bounded async facade. The caller owns DB pool and server-thread dispatch. */
public final class LinkService implements LinkApi, AutoCloseable {
    private final EventStore events;
    private final SessionStore sessions;
    private final NetworkStore network;
    private final RouteStore routes;
    private final TeleportStore teleports;
    private final ExecutorService executor;
    private final java.util.function.Consumer<RouteStore.Request> routeSubmitted;
    public LinkService(Database database) {
        this(database, request -> {});
    }
    public LinkService(Database database, java.util.function.Consumer<RouteStore.Request> routeSubmitted) {
        this.routeSubmitted=Objects.requireNonNull(routeSubmitted);
        events=new EventStore(database); sessions=new SessionStore(database,30); network=new NetworkStore(database);
        routes=new RouteStore(database);
        teleports=new TeleportStore(database);
        // FIFO submission order within one runtime. Across runtimes, versions and
        // fencing reject stale work; callers must explicitly reconcile conflicts.
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1024),
                Thread.ofPlatform().name("Link-Database-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    @FunctionalInterface private interface Task<T> { T run() throws Exception; }
    private <T> CompletionStage<T> async(Task<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {return task.run();} catch(Exception e) {throw new CompletionException(e);}
            },executor);
        } catch(RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }
    @Override public Data data() { return new Data() {
        public CompletionStage<Optional<State>> read(String id) { return async(() -> events.read(id)); }
        public CompletionStage<Commit> commit(Transaction tx) { return async(() -> {
            if(tx.changes().stream().anyMatch(c->c.id().startsWith("native:")||c.id().startsWith("player:")||c.id().startsWith("warp:")||c.id().startsWith("tpa:")))
                throw new IllegalArgumentException("Reserved state: use engine().transact for native state or the network warp API");
            return events.commit(tx);
        }); }
        public CompletionStage<List<Event>> history(String id,long after,int limit) { return async(() -> events.history(id,after,limit)); }
    }; }
    @Override public Sessions sessions() { return new Sessions() {
        public CompletionStage<Session> acquire(UUID player,String node) {return async(() -> sessions.acquire(player,node));}
        public CompletionStage<Session> renew(Session session) {return async(() -> sessions.renew(session));}
        public CompletionStage<Void> release(Session session) {return async(() -> {sessions.release(session); return null;});}
        public CompletionStage<Transfer> prepare(Session s,UUID op,Destination d) {return async(() -> sessions.prepare(s,op,d));}
        public CompletionStage<Session> accept(UUID p,UUID t,String node) {return async(() -> sessions.accept(p,t,node));}
    }; }
    @Override public Teleports teleports(){return new Teleports(){
        public CompletionStage<TeleportRequest> request(UUID player,String recipient,boolean here){return async(()->teleports.request(player,recipient,here));}
        public CompletionStage<TeleportRequest> respond(UUID player,String requester,boolean accept){return async(()->teleports.respond(player,requester,accept));}
        public CompletionStage<Boolean> cancel(UUID player){return async(()->teleports.cancel(player));}
        public CompletionStage<List<TeleportRequest>> incoming(UUID player){return async(()->teleports.incoming(player));}
        public CompletionStage<List<String>> onlinePlayers(){return async(teleports::onlinePlayers);}
    };}
    public CompletionStage<Void> rejectTeleport(UUID id){return async(()->{teleports.finish(id,false);return null;});}
    public CompletionStage<Void> submitTeleport(TeleportStore.Work task,Destination destination,int capacity){return async(()->{
        UUID id=task.request().id();
        if(!routes.status(id).equals("MISSING")){teleports.finish(id,true);return null;}
        UUID mover=task.request().here()?task.request().recipient():task.request().requester();
        try{
            Destination fixed=teleports.resolve(id,destination);
            teleports.dispatchable(id);network.prepareDestination(fixed,capacity);
            Reservation reservation=network.reserve(mover,id,fixed);routes.request(id,reservation);teleports.finish(id,true);
            try{routeSubmitted.accept(new RouteStore.Request(id,reservation));}catch(RuntimeException ignored){}
        }catch(TeleportStore.Rejected|ConflictException error){
            if(!routes.status(id).equals("MISSING"))teleports.finish(id,true);
            else{network.cancel(id,mover);teleports.finish(id,false);}
            throw error;
        }
        return null;
    });}
    @Override public Network network() { return new Network() {
        public CompletionStage<List<Channel>> channels() {return async(network::channels);}
        public CompletionStage<Void> registerChannel(String id,Destination spawn,int capacity){return async(()->{network.register(id,spawn,capacity);return null;});}
        public CompletionStage<Void> setChannelReady(String id,boolean ready){return async(()->{network.ready(id,ready);return null;});}
        public CompletionStage<Reservation> reserve(UUID player,UUID request) {return async(() -> network.reserve(player,request));}
        public CompletionStage<Reservation> reserve(UUID player,UUID request,Destination destination) {return async(() -> network.reserve(player,request,destination));}
        public CompletionStage<Void> cancel(UUID r,UUID p) {return async(() -> {network.cancel(r,p);return null;});}
        public CompletionStage<Optional<Destination>> warp(String name) {return async(() -> network.warp(name));}
        public CompletionStage<Void> setWarp(String name,Destination d) {return async(() -> {network.setWarp(name,d); return null;});}
        public CompletionStage<Void> setWarp(String name,Destination d,int capacity) {return async(() -> {network.setWarp(name,d,capacity); return null;});}
        public CompletionStage<Boolean> deleteWarp(String name){return async(()->network.deleteWarp(name));}
        public CompletionStage<List<String>> warps(){return async(network::warps);}
        public CompletionStage<Reservation> requestTravel(UUID player,UUID operation,Destination destination){return async(()->{
            Reservation reservation=network.reserve(player,operation,destination);
            try{routes.request(operation,reservation);}
            catch(Exception error){try{network.cancel(operation,player);}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
            // This is a wake-up hint after durable submission. Polling remains the recovery path.
            try{routeSubmitted.accept(new RouteStore.Request(operation,reservation));}
            catch(RuntimeException ignored){}
            return reservation;
        });}
        public CompletionStage<String> travelStatus(UUID operation){return async(()->routes.status(operation));}
    }; }
    @Override public void close() {
        executor.shutdown();
        try { if(!executor.awaitTermination(15,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch(InterruptedException e) {executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
