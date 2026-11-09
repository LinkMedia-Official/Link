package io.linkmc.integration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.item.*;
import java.util.*;

/** Test-only: server-thread cost of the per-player checkpoint capture, using synthetic players with full inventories. */
final class CaptureBench {
    private CaptureBench(){}
    static void run(int players)throws Exception{
        MinecraftServer server=MinecraftServer.getServer();
        ServerLevel level=((org.bukkit.craftbukkit.CraftWorld)org.bukkit.Bukkit.getWorlds().getFirst()).getHandle();
        var capture=Class.forName("io.linkmc.server.NativePlayerCodec").getDeclaredMethod("capture",ServerPlayer.class);capture.setAccessible(true);
        List<ServerPlayer> list=new ArrayList<>();
        Item[] kinds={Items.DIAMOND_SWORD,Items.DIAMOND_PICKAXE,Items.NETHERITE_CHESTPLATE,Items.BOW,Items.COOKED_BEEF,Items.OAK_LOG,Items.WRITTEN_BOOK,Items.SHULKER_BOX,Items.ENCHANTED_BOOK};
        for(int i=0;i<players;i++){
            var profile=new com.mojang.authlib.GameProfile(new UUID(0x4C494E4BL,i),"Bench"+i);
            ServerPlayer player=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
            for(int slot=0;slot<36;slot++){
                ItemStack stack=new ItemStack(kinds[slot%kinds.length],slot%kinds.length>=4?32:1);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("bench item "+slot));
                player.getInventory().setItem(slot,stack);
            }
            for(int slot=0;slot<27;slot++)player.getEnderChestInventory().setItem(slot,new ItemStack(kinds[slot%kinds.length],1));
            list.add(player);
        }
        for(var p:list)capture.invoke(null,p); // warm up (advancement/stat trackers, codecs)
        long best=Long.MAX_VALUE,total=0;int rounds=5;
        for(int round=0;round<rounds;round++){
            long start=System.nanoTime();
            for(var p:list)capture.invoke(null,p);
            long elapsed=System.nanoTime()-start;best=Math.min(best,elapsed);total+=elapsed;
        }
        org.bukkit.Bukkit.getLogger().info(String.format("LINK_CAPTURE_BENCH players=%d best=%.1fms avg=%.1fms perPlayer=%.3fms",
                players,best/1e6,total/1e6/rounds,best/1e6/players));
    }
}
