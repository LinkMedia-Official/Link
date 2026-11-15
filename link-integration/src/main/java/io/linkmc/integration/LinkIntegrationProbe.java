package io.linkmc.integration;

import io.linkmc.api.Link;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Install only in disposable runtime tests. Never included in the Link server artifact. */
public final class LinkIntegrationProbe extends JavaPlugin implements org.bukkit.event.Listener {
    private java.util.UUID travelId,travelPlayer;
    private boolean cancelTeleport;
    @org.bukkit.event.EventHandler public void teleport(org.bukkit.event.player.PlayerTeleportEvent event){if(cancelTeleport){cancelTeleport=false;event.setCancelled(true);getLogger().info("TRAVEL_CANCELLED_OK");}}
    private io.linkmc.api.LinkApi.Destination travelDestination;
    @org.bukkit.event.EventHandler public void prelogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event){
        var offline=Bukkit.getOfflinePlayer(event.getUniqueId());offline.getName();offline.getFirstPlayed();offline.getLastPlayed();
        getLogger().info("LINK_PRELOGIN_READ_OK "+event.getName());
    }
    @Override public void onEnable(){
        if(!System.getenv().getOrDefault("LINK_NODE_ID","").startsWith("test-"))throw new IllegalStateException("Integration probe requires test-* node");
        Bukkit.getPluginManager().registerEvents(this,this);
        getCommand("linkprobe").setExecutor((sender,command,label,args)->{
            if(sender instanceof Player||args.length!=1)return false;
            if(args.length==1&&args[0].startsWith("bench-players-")){try{CaptureBench.run(Integer.parseInt(args[0].substring("bench-players-".length())));}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"LINK_CAPTURE_BENCH_FAILED",e);}return true;}
            if(args[0].startsWith("travel-")){
                if(!args[0].equals("travel-repeat")){
                    var player=java.util.Objects.requireNonNull(Bukkit.getPlayerExact("LinkWire"));travelPlayer=player.getUniqueId();travelId=java.util.UUID.randomUUID();
                    boolean local=!args[0].equals("travel-remote");cancelTeleport=args[0].equals("travel-cancel");
                    travelDestination=new io.linkmc.api.LinkApi.Destination(local?System.getenv("LINK_NODE_ID"):"test-b",args[0].equals("travel-world")?"world_probe":Bukkit.getWorlds().getFirst().getName(),local?6:8,66,local?6:8,90,0);
                }
                var operation=travelId;
                for(int i=0;i<8;i++)Link.api().network().requestTravel(travelPlayer,operation,travelDestination).whenComplete((r,e)->{
                    if(e!=null)getLogger().log(java.util.logging.Level.SEVERE,"TRAVEL_REQUEST_FAILED",e);else getLogger().info("TRAVEL_REQUEST_OK "+operation);
                });return true;
            }
            boolean prepare=args[0].equals("prepare");
            Link.api().engine().transact(()->{
                if(args[0].startsWith("matrix-")){GameplayMatrix.run(args[0]);return;}
                if(args[0].startsWith("utility-")){
                    Player p=java.util.Objects.requireNonNull(Bukkit.getPlayerExact("LinkWire"));
                    if(args[0].equals("utility-prepare")){p.setGameMode(GameMode.SURVIVAL);p.setHealth(6);p.setFoodLevel(2);p.setSaturation(0);p.setExhaustion(3);return;}
                    if(!p.getAllowFlight()||!p.isFlying()||Math.abs(p.getFlySpeed()-0.3f)>0.0001||Math.abs(p.getWalkSpeed()-(0.2+0.8/9))>0.0001||p.getHealth()!=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()||p.getFoodLevel()!=20)throw new AssertionError("Utility state mismatch: flight="+p.getAllowFlight()+"/"+p.isFlying()+" speed="+p.getFlySpeed()+"/"+p.getWalkSpeed()+" health="+p.getHealth()+" food="+p.getFoodLevel());
                    getLogger().info("UTILITY_STATE_VERIFIED");return;
                }
                if(args[0].equals("platform")){
                    World world=java.util.Objects.requireNonNull(Bukkit.getWorlds().getFirst());
                    world.setDifficulty(Difficulty.NORMAL);
                    for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)world.getBlockAt(x,64,z).setType(Material.BEDROCK);
                    return;
                }
                if(args[0].startsWith("wire-")){
                    var player=java.util.Objects.requireNonNull(Bukkit.getPlayerExact("LinkWire"),"Wire client not on this backend");
                    switch(args[0]){
                        case "wire-prepare" -> {
                            Bukkit.getWorlds().forEach(w->w.setDifficulty(Difficulty.NORMAL));
                            player.getInventory().clear();player.getInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                            player.getEnderChest().setItem(0,new ItemStack(Material.EMERALD,3));player.setHealth(13);player.setLevel(9);player.setFoodLevel(6);player.setSaturation(0);
                            player.setInvulnerable(true);player.setAllowFlight(true);player.setFlying(true);
                        }
                        case "wire-transfer" -> Link.api().network().requestTravel(player.getUniqueId(),java.util.UUID.randomUUID(),new io.linkmc.api.LinkApi.Destination("test-b",Bukkit.getWorlds().getFirst().getName(),8,66,8,90,0)).whenComplete((r,e)->{if(e!=null)getLogger().log(java.util.logging.Level.SEVERE,"WIRE_TRANSFER_FAILED",e);else getLogger().info("WIRE_TRANSFER_REQUESTED "+r.id());});
                        case "wire-state", "wire-verify" -> {
                            var item=player.getInventory().getItem(0);var ender=player.getEnderChest().getItem(0);
                            if(item==null||item.getType()!=Material.DIAMOND||item.getAmount()!=7||ender==null||ender.getAmount()!=3||player.getLevel()!=9||player.getHealth()!=13)throw new AssertionError("Wire state: item="+item+", ender="+ender+", level="+player.getLevel()+", health="+player.getHealth());
                            if(args[0].equals("wire-verify")&&(player.getLocation().getX()!=8||player.getLocation().getZ()!=8))throw new AssertionError("Wire arrival location mismatch");
                        }
                        default -> throw new IllegalArgumentException("Unknown wire probe");
                    }return;
                }
                if(args[0].startsWith("player-")){NativePlayerProbe.run(args[0]);return;}
                World world=java.util.Objects.requireNonNull(Bukkit.getWorlds().getFirst());
                world.getChunkAt(0,0).load();
                if(prepare){
                    world.getBlockAt(1,64,1).setType(Material.BEDROCK);
                    world.getBlockAt(1,65,1).setType(Material.CHEST);
                    ((Chest)world.getBlockAt(1,65,1).getState()).getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                    var stand=world.spawn(new Location(world,3,65,3),ArmorStand.class);
                    stand.setGravity(false);stand.setPersistent(true);stand.addScoreboardTag("link-probe");stand.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    world.getPersistentDataContainer().set(new NamespacedKey(this,"marker"),org.bukkit.persistence.PersistentDataType.INTEGER,42);
                    world.setTime(6000);
                    var board=Bukkit.getScoreboardManager().getMainScoreboard();
                    var objective=board.getObjective("linkprobe");if(objective==null)objective=board.registerNewObjective("linkprobe",org.bukkit.scoreboard.Criteria.DUMMY,net.kyori.adventure.text.Component.text("probe"));
                    objective.getScore("marker").setScore(17);
                }else{
                    if(!(world.getBlockAt(1,65,1).getState() instanceof Chest chest)||chest.getBlockInventory().getItem(0)==null||chest.getBlockInventory().getItem(0).getType()!=Material.DIAMOND||chest.getBlockInventory().getItem(0).getAmount()!=7)throw new AssertionError("Chest inventory did not recover");
                    long count=world.getEntitiesByClass(ArmorStand.class).stream().filter(e->e.getScoreboardTags().contains("link-probe")&&e.getEquipment().getHelmet().getType()==Material.GOLDEN_HELMET).count();
                    if(count!=1)throw new AssertionError("Entity recovery count="+count);
                    if(!Integer.valueOf(42).equals(world.getPersistentDataContainer().get(new NamespacedKey(this,"marker"),org.bukkit.persistence.PersistentDataType.INTEGER)))throw new AssertionError("PDC did not recover");
                    var objective=Bukkit.getScoreboardManager().getMainScoreboard().getObjective("linkprobe");
                    if(objective==null||objective.getScore("marker").getScore()!=17)throw new AssertionError("Scoreboard did not recover");
                }
            }).whenComplete((v,error)->{if(error==null)getLogger().info((args[0].startsWith("matrix-")?"LINK_PROBE_COMMITTED ":"LINK_PROBE_OK ")+args[0]);else getLogger().log(java.util.logging.Level.SEVERE,"LINK_PROBE_FAILED",error);});
            return true;
        });
    }
    @Override public void onDisable(){NativePlayerProbe.disconnect();}
}
