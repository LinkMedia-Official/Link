package io.linkmc.core;

import io.linkmc.api.LinkApi.Worlds;
import io.linkmc.api.LinkApi.WorldInfo;
import io.linkmc.api.LinkApi;
import java.util.List;
import java.util.concurrent.*;

/** Serializes lifecycle operations and delegates all live-world work to a tick-thread driver. */
public final class WorldService implements Worlds, AutoCloseable {
    public interface Driver {
        WorldInfo createEditor(String name) throws Exception;
        /** Must obtain a stable snapshot, not concurrently serialize a live mutable world. */
        byte[] snapshot(String name) throws Exception;
        WorldInfo instantiate(String name,byte[] srf,boolean persistent) throws Exception;
        WorldInfo load(String name) throws Exception;
        void save(String name) throws Exception;
        void unload(String name) throws Exception;
        void delete(String name) throws Exception;
        List<WorldInfo> loaded() throws Exception;
    }
    private final Driver driver;
    private final TemplateStore templates;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),Thread.ofPlatform().name("Link-Worlds-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public WorldService(Driver driver,TemplateStore templates) {this.driver=driver;this.templates=templates;}
    @FunctionalInterface private interface Task<T> {T run() throws Exception;}
    private <T> CompletionStage<T> run(Task<T> task) {
        try {return CompletableFuture.supplyAsync(() -> {try{return task.run();}catch(Exception e){throw new CompletionException(e);}},worker);}
        catch(RejectedExecutionException e){return CompletableFuture.failedFuture(e);}
    }
    public CompletionStage<List<String>> templates(){return run(templates::list);}
    public CompletionStage<WorldInfo> createEditor(String name){LinkApi.requireName(name);return run(() -> driver.createEditor(name));}
    public CompletionStage<Void> publish(String editor,String template,int version){LinkApi.requireName(editor);LinkApi.requireName(template);version(version);return run(() -> {templates.publish(template,version,driver.snapshot(editor));return null;});}
    public CompletionStage<WorldInfo> create(String name,String template,int version,boolean persistent){LinkApi.requireName(name);LinkApi.requireName(template);version(version);return run(() -> driver.instantiate(name,templates.read(template,version),persistent));}
    private static void version(int version){if(version<1)throw new IllegalArgumentException("Template version must be positive");}
    public CompletionStage<WorldInfo> load(String name){return run(() -> driver.load(name));}
    public CompletionStage<Void> save(String name){return run(() -> {driver.save(name);return null;});}
    public CompletionStage<Void> unload(String name){return run(() -> {driver.unload(name);return null;});}
    public CompletionStage<Void> delete(String name){return run(() -> {driver.delete(name);return null;});}
    public CompletionStage<List<WorldInfo>> loaded(){return run(() -> List.copyOf(driver.loaded()));}
    public void close(){
        worker.shutdownNow();
        try {
            if(!worker.awaitTermination(15,TimeUnit.SECONDS))throw new IllegalStateException("World operations did not stop; do not release ownership");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("World shutdown interrupted",e);}
    }
}
