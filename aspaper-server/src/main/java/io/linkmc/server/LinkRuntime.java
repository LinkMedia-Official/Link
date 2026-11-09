package io.linkmc.server;

/**
 * Java entry point called by the Link Minecraft patches (bootstrap/shutdown). The implementation is Scala
 * ({@code LinkRuntimeImpl}); these methods only forward to it.
 */
public final class LinkRuntime {
    private LinkRuntime() {}
    private static final LinkServerBridge BRIDGE = LinkServerBridge.load();

    public static void beforeWorlds() { BRIDGE.beforeWorlds(); }
    public static void afterWorlds() { BRIDGE.afterWorlds(); }
    public static void beginShutdown() { BRIDGE.beginShutdown(); }
    public static void stop() { BRIDGE.stop(); }
    public static boolean managesWorlds() { return BRIDGE.managesWorlds(); }
}
