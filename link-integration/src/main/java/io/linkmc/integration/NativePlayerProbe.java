package io.linkmc.integration;

import io.linkmc.core.*;
import io.linkmc.server.NativeJournal;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.inventory.*;
import java.util.*;

/** Synthetic native player: exercises NBT/cursor recovery, not real-client authentication. */
final class NativePlayerProbe {
    private static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000123");
    private static ServerPlayer active;
    static void disconnect(){if(active!=null){NativeJournal.left(active);active.connection.connection.channel.close();active=null;}}
    static void run(String mode){
        if(mode.equals("player-transfer")){
            io.linkmc.api.Link.api().network().requestTravel(ID,UUID.randomUUID(),new io.linkmc.api.LinkApi.Destination("test-b",org.bukkit.Bukkit.getWorlds().getFirst().getName(),8,66,8,90,0)).whenComplete((r,e)->{
                if(e!=null)org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE,"LINK_TRANSFER_FAILED",e);
                else org.bukkit.Bukkit.getLogger().info("LINK_TRANSFER_REQUESTED "+r.id());
            });return;
        }
        boolean prepare=mode.equals("player-prepare"),arriving=mode.equals("player-arrive");
        try{
            Database db=new Database(()->java.sql.DriverManager.getConnection(System.getenv("LINK_JDBC_URL"),System.getenv("LINK_DB_USER"),System.getenv("LINK_DB_PASSWORD")));
            if(!arriving){
                UUID ticket=UUID.randomUUID();var reservation=new NetworkStore(db).reserve(ID,ticket,new io.linkmc.api.LinkApi.Destination(System.getenv("LINK_NODE_ID"),org.bukkit.Bukkit.getWorlds().getFirst().getName(),1,66,1,0,0));
                new ArrivalStore(db).issue(ticket,reservation,null);
            }
            Connection connection=new Connection(PacketFlow.SERVERBOUND);
            connection.channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter(){
                @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise){io.netty.util.ReferenceCountUtil.release(message);promise.setSuccess();}
            });
            connection.address=new java.net.InetSocketAddress("127.0.0.1",25599);connection.preparing=false;
            NativeJournal.prepare(ID,connection);
            MinecraftServer server=MinecraftServer.getServer();
            ServerLevel level=((org.bukkit.craftbukkit.CraftWorld)org.bukkit.Bukkit.getWorlds().getFirst()).getHandle();
            var profile=new com.mojang.authlib.GameProfile(ID,"LinkProbe");
            ServerPlayer player=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
            new ServerGamePacketListenerImpl(server,connection,player,CommonListenerCookie.createInitial(profile,false));
            try(var reporter=new net.minecraft.util.ProblemReporter.ScopedCollector(player.problemPath(),MinecraftServer.LOGGER)){
                NativeJournal.playerData(ID).ifPresent(tag->player.load(net.minecraft.world.level.storage.TagValueInput.create(reporter,server.registryAccess(),tag)));
            }
            var destination=NativeJournal.destination(ID);
            if(destination!=null)player.snapTo(destination.x(),destination.y(),destination.z(),destination.yaw(),destination.pitch());NativeJournal.joined(player);active=player;
            var block=org.bukkit.Bukkit.getWorlds().getFirst().getBlockAt(5,65,5);
            if(prepare){
                block.setType(org.bukkit.Material.CHEST);
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(new net.minecraft.core.BlockPos(5,65,5));
                chest.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,7));
                player.containerMenu=ChestMenu.threeRows(1,player.getInventory(),chest);
                player.containerMenu.clicked(0,0,ContainerInput.PICKUP,player);
                if(player.containerMenu.getCarried().getCount()!=7||!chest.getItem(0).isEmpty())throw new AssertionError("Native chest click did not move item to cursor");
                player.setHealth(13);player.experienceLevel=9;
                player.getEnderChestInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD,3));
            }else{
                int diamonds=0;for(int slot=0;slot<player.getInventory().getContainerSize();slot++){var item=player.getInventory().getItem(slot);if(item.is(net.minecraft.world.item.Items.DIAMOND))diamonds+=item.getCount();}
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(new net.minecraft.core.BlockPos(5,65,5));
                if(diamonds!=7||(!arriving&&(chest==null||!chest.getItem(0).isEmpty())))throw new AssertionError("Cursor/chest duplication or loss: diamonds="+diamonds);
                if(arriving&&(player.getX()!=8||player.getZ()!=8||player.getYRot()!=90))throw new AssertionError("Reserved destination not applied");
                if(player.getHealth()!=13||player.experienceLevel!=9||player.getEnderChestInventory().getItem(0).getCount()!=3)throw new AssertionError("Player attributes/ender inventory did not recover");
            }
        }catch(Exception e){throw new IllegalStateException("Native player integration probe failed",e);}
    }
}
