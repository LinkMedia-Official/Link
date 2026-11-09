package io.linkmc.server;

import com.google.gson.JsonParser;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.*;
import java.io.*;

/** Player NBT plus state omitted by vanilla player.dat (cursor, transient menu, stats, advancements). */
final class NativePlayerCodec {
    private NativePlayerCodec(){}
    static byte[] encode(ServerPlayer player)throws Exception{
        return compress(capture(player));
    }
    /** Only called on the server thread; returned NBT owns all mutable arrays. */
    static CompoundTag capture(ServerPlayer player)throws Exception{
        try(var reporter=new ProblemReporter.ScopedCollector(player.problemPath(),net.minecraft.server.MinecraftServer.LOGGER)){
            TagValueOutput output=TagValueOutput.createWithContext(reporter,player.registryAccess());
            player.saveWithoutId(output);
            // These entities are part of the same SRF world snapshot; never spawn a second copy from player NBT.
            CompoundTag tag=output.buildResult();tag.remove("RootVehicle");tag.remove("ender_pearls");
            tag.putString("LinkStats",player.getStats().linkSnapshot().toString());
            tag.putString("LinkAdvancements",player.getAdvancements().linkSnapshot().toString());
            TagValueOutput cursor=TagValueOutput.createWithContext(reporter,player.registryAccess());
            cursor.store("item",ItemStack.OPTIONAL_CODEC,player.containerMenu.getCarried());tag.put("LinkCursor",cursor.buildResult());
            // Snapshot only non-player, non-block menu inventories. On reconnect they are recovered
            // into the player's inventory; result slots are derived and must never be duplicated.
            ListTag recovery=new ListTag();
            java.util.Map<net.minecraft.world.Container,java.util.Set<Integer>> seen=new java.util.IdentityHashMap<>();
            var menu=player.containerMenu;
            boolean transientInputs=menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu
                    || menu instanceof net.minecraft.world.inventory.ItemCombinerMenu
                    || menu instanceof net.minecraft.world.inventory.EnchantmentMenu
                    || menu instanceof net.minecraft.world.inventory.StonecutterMenu
                    || menu instanceof net.minecraft.world.inventory.LoomMenu
                    || menu instanceof net.minecraft.world.inventory.GrindstoneMenu
                    || menu instanceof net.minecraft.world.inventory.MerchantMenu;
            for(var slot:player.containerMenu.slots){
                if(!transientInputs || slot.container instanceof net.minecraft.world.inventory.ResultContainer)continue;
                if(slot instanceof net.minecraft.world.inventory.ResultSlot || slot instanceof net.minecraft.world.inventory.MerchantResultSlot)continue;
                if(slot.container==player.getInventory() || slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity || slot.container instanceof net.minecraft.world.entity.Entity)continue;
                if(!seen.computeIfAbsent(slot.container,key->new java.util.HashSet<>()).add(slot.getContainerSlot())||slot.getItem().isEmpty())continue;
                TagValueOutput item=TagValueOutput.createWithContext(reporter,player.registryAccess());item.store("item",ItemStack.OPTIONAL_CODEC,slot.getItem());recovery.add(item.buildResult());
            }
            tag.put("LinkRecovery",recovery);
            return tag.copy();
        }
    }
    static byte[] compress(CompoundTag tag)throws IOException{
        var bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,bytes);return bytes.toByteArray();
    }
    static CompoundTag decode(byte[] bytes)throws IOException{return NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtAccounter.create(32L*1024*1024));}
    static void recover(ServerPlayer player,CompoundTag tag){
        try(var reporter=new ProblemReporter.ScopedCollector(player.problemPath(),net.minecraft.server.MinecraftServer.LOGGER)){
            var input=TagValueInput.create(reporter,player.registryAccess(),tag);
            input.child("LinkCursor").flatMap(v->v.read("item",ItemStack.OPTIONAL_CODEC)).ifPresent(item->give(player,item));
            var list=tag.getListOrEmpty("LinkRecovery");
            for(int i=0;i<list.size();i++)TagValueInput.create(reporter,player.registryAccess(),list.getCompoundOrEmpty(i)).read("item",ItemStack.OPTIONAL_CODEC).ifPresent(item->give(player,item));
        }
    }
    private static void give(ServerPlayer player,ItemStack item){
        if(item.isEmpty())return;
        if(!player.getInventory().add(item))player.drop(item,false,net.minecraft.util.Prediction.PREDICTED);
    }
}
