package us.talabrek.ultimateskyblock.event;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.EndGateway;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import io.papermc.lib.PaperLib;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.api.async.Callback;
import us.talabrek.ultimateskyblock.api.event.IslandInfoEvent;
import us.talabrek.ultimateskyblock.api.model.IslandScore;
import us.talabrek.ultimateskyblock.challenge.ChallengeCompletion;
import us.talabrek.ultimateskyblock.challenge.ChallengeCompletionLogic;
import us.talabrek.ultimateskyblock.handler.VaultHandler;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.BlockLimitLogic;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.island.task.SetBiomeTask;
import us.talabrek.ultimateskyblock.player.PatienceTester;
import us.talabrek.ultimateskyblock.player.Perk;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent;
import com.meowj.langutils.lang.LanguageHelper;
import com.sk89q.worldedit.event.platform.BlockInteractEvent;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class PlayerEvents implements Listener {
    private static final Set<EntityDamageEvent.DamageCause> FIRE_TRAP = new HashSet<>(
            Arrays.asList(EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK));
    private static final Random RANDOM = new Random();
    private static final int OBSIDIAN_SPAM = 10000; // Max once every 10 seconds.

    private final uSkyBlock plugin;
    private final boolean visitorFallProtected;
    private final boolean visitorFireProtected;
    private final boolean visitorMonsterProtected;
    private final boolean protectLava;
    private final Map<UUID, Long> obsidianClick = new WeakHashMap<>();
    private final boolean blockLimitsEnabled;

    public PlayerEvents(uSkyBlock plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        visitorFallProtected = config.getBoolean("options.protection.visitors.fall", true);
        visitorFireProtected = config.getBoolean("options.protection.visitors.fire-damage", true);
        visitorMonsterProtected = config.getBoolean("options.protection.visitors.monster-damage", false);
        protectLava = config.getBoolean("options.protection.protect-lava", true);
        blockLimitsEnabled = config.getBoolean("options.island.block-limits.enabled", false);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerFoodChange(final FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player && plugin.getWorldManager().isSkyWorld(event.getEntity().getWorld())) {
            Player hungerman = (Player) event.getEntity();
            float randomNum = RANDOM.nextFloat();
            if (plugin.getWorldManager().isSkyWorld(hungerman.getWorld())
                    && hungerman.getFoodLevel() > event.getFoodLevel()
                    && plugin.playerIsOnIsland(hungerman)) {
                Perk perk = plugin.getPerkLogic().getPerk(hungerman);
                if (randomNum <= perk.getHungerReduction()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClickOnObsidian(final PlayerInteractEvent event) {
        if (!plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            return;
        }
        long now = System.currentTimeMillis();
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        Block block = event.getClickedBlock();
        Long lastClick = obsidianClick.get(player.getUniqueId());
        if (lastClick != null && (lastClick + OBSIDIAN_SPAM) >= now) {
            plugin.notifyPlayer(player, tr("\u00a74You can only convert obsidian once every 10 seconds"));
            return;
        }
        if (Settings.extras_obsidianToLava && plugin.playerIsOnIsland(player)
                && plugin.getWorldManager().isSkyWorld(player.getWorld())
                && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && player.getItemInHand() != null
                && player.getItemInHand().getType() == Material.BUCKET
                && block != null
                && block.getType() == Material.OBSIDIAN
                && !testForObsidian(block)) {
            if (inventory.firstEmpty() != -1) {
                obsidianClick.put(player.getUniqueId(), now);
                player.sendMessage(tr("\u00a7eChanging your obsidian back into lava. Be careful!"));
                inventory.removeItem(new ItemStack(Material.BUCKET, 1));
                inventory.addItem(new ItemStack(Material.LAVA_BUCKET, 1));
                player.updateInventory();
                block.setType(Material.AIR);
                event.setCancelled(true); // Don't execute the click anymore (since that would re-place the lava).
            } else {
                player.sendMessage(tr("\u00a7eYour inventory must have another empty space!"));
            }
        }
    }

    /**
     * Tests for more than one obsidian close by.
     */
    public boolean testForObsidian(final Block block) {
        for (int x = -3; x <= 3; ++x) {
            for (int y = -3; y <= 3; ++y) {
                for (int z = -3; z <= 3; ++z) {
                    final Block testBlock = block.getWorld().getBlockAt(block.getX() + x, block.getY() + y, block.getZ() + z);
                    if ((x != 0 || y != 0 || z != 0) && testBlock.getType() == Material.OBSIDIAN) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onLavaReplace(BlockPlaceEvent event) {
        if (!protectLava || !plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            return; // Skip
        }
        if (isLavaSource(event.getBlockReplacedState().getType(), event.getBlockReplacedState().getRawData())) {
            plugin.notifyPlayer(event.getPlayer(), tr("\u00a74It''s a bad idea to replace your lava!"));
            event.setCancelled(true);
        }
    }

    private boolean isLavaSource(Material type, byte data) {
        return (type == Material.LAVA) && data == 0;
    }

    @EventHandler
    public void onLavaAbsorption(EntityChangeBlockEvent event) {
        if (!plugin.getWorldManager().isSkyWorld(event.getBlock().getWorld())) {
            return;
        }
        if (isLavaSource(event.getBlock().getType(), event.getBlock().getData())) {
            if (event.getTo() != Material.LAVA) {
                event.setCancelled(true);
                // TODO: R4zorax - 21-07-2018: missing datavalue (might convert stuff - exploit)
                ItemStack item = new ItemStack(event.getTo(), 1);
                Location above = event.getBlock().getLocation().add(0, 1, 0);
                event.getBlock().getWorld().dropItemNaturally(above, item);
            }
        }
    }
        //WAIT FOR paper
//    @EventHandler(priority = EventPriority.LOWEST)
//    public void onPlayerChangeWorld(final PlayerChangedWorldEvent event){
//        if(plugin.getWorldManager().isSkyGrid(event.getPlayer().getWorld())){
//            event.getPlayer().setViewDistance(2);
//        }else{
//            event.getPlayer().setViewDistance(4);
//        }
//    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerEndPortal(PlayerPortalEvent event){
        if(event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL){
            event.setCancelled(true);
            Player p = event.getPlayer();
            IslandInfo is = plugin.getIslandInfo(p.getLocation());
            if(is == null) {
                is = plugin.getIslandInfo(p);
                if(is == null) return;
            }
            PlayerInfo pi = plugin.getPlayerInfo(is.getLeaderUniqueId());
            boolean isFirstCompletion = pi.checkChallenge("builder10") == 0;
            if(isFirstCompletion){
                plugin.notifyPlayer(p, (tr("\u00a7cYou do not have permission. Complete adept challenges to unlock this command. ")));
                return;
            }
            WorldGuardHandler.updateEndRegion(plugin, p, is);
            Location l = is.getIslandLocation();
            l.setY(64);
            l.setX(l.getBlockX()+0.5);
            l.setZ(l.getBlockZ()+0.5);
            World end = plugin.getWorldManager().getEndWorld();
            for (int i = l.getBlockX()-2; i <=l.getBlockX()+2; i++){
                for (int j = l.getBlockZ()-2; j<=l.getBlockZ()+2; j++){
                    end.getBlockAt(i, 63, j).setType(Material.AIR);
                    end.getBlockAt(i, 63, j).setType(Material.OBSIDIAN);
                }
            }
            l.setWorld(end);
            new SetBiomeTask(plugin, l, Biome.THE_END,()->{});
            PaperLib.teleportAsync(p,l);
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPortalEvent(EntityPortalEvent event){
        if(event.getFrom().getBlock().getType() != Material.NETHER_PORTAL){
            event.setCancelled(true);
            IslandInfo is = plugin.getIslandInfo(event.getFrom());
            if(is == null)return;
            Location l = is.getIslandLocation();
            l.setY(64);
            l.setX(l.getBlockX()+0.5);
            l.setZ(l.getBlockZ()+0.5);
            World end = plugin.getWorldManager().getEndWorld();
            l.setWorld(end);
            if(event.getEntity() instanceof FallingBlock){
                FallingBlock fb = (FallingBlock)event.getEntity();
                PaperLib.teleportAsync(event.getEntity(), l);
                Location l2 = event.getFrom();
                if(l2.getBlockY()<=1)return;
                l2.setY(l2.getBlockY()-1);
                if(end.getBlockAt(l2).getType()!=Material.AIR)return;
                end.spawnFallingBlock(l2, fb.getBlockData());
                return;
            }
            if(event.getEntity() instanceof Item){
                Item it = (Item)event.getEntity();
                ItemStack stack = it.getItemStack();
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore();
                    if (lore != null && !lore.isEmpty()) {
                        lore.removeIf(line -> line.contains("Owner: "));
                        meta.setLore(lore);
                        stack.setItemMeta(meta);
                        it.setItemStack(stack);
                    }
                }
                end.dropItem(l, stack);
                it.remove();
                return;
            }
            PaperLib.teleportAsync(event.getEntity(), l);
            return;
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerEndGateway(PlayerTeleportEndGatewayEvent event){
        Location loc = event.getGateway().getExitLocation();
        ///setblock ~ ~ ~ minecraft:end_gateway{Age:61824,ExactTeleport:1,ExitPortal:{X:70,Y:156,Z:24}} replace
        if(loc.getBlockX()==70 && loc.getBlockY()==156 && loc.getBlockZ()==24){
            event.getPlayer().performCommand("is grid");
            event.setCancelled(true);
        }
    }

    // TODO 2018-11-09 Muspah: Move (parts) to new EntityDamageByEntityEvent-handler
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVisitorDamage(final EntityDamageEvent event) {
        // Only protect things in the Skyworld.
        if (!plugin.getWorldManager().isSkyWorld(event.getEntity().getWorld())) {
            return;
        }

        // Only protect visitors against damage if pvp is disabled:
        if (Settings.island_allowPvP) {
            return;
        }

        // Don't protect players in spawn
        if (WorldGuardHandler.isInSpawn(event.getEntity().getLocation())) {
            return;
        }
        // This protection only applies to players:
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        // Don't protect players on their own islands:
        if (plugin.playerIsOnIsland((Player) event.getEntity())) {
            return;
        }

        if ((visitorFireProtected && FIRE_TRAP.contains(event.getCause()))
                || (visitorFallProtected && (event.getCause() == EntityDamageEvent.DamageCause.FALL))
                || (visitorMonsterProtected &&
                    (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                    || event.getCause() == EntityDamageEvent.DamageCause.MAGIC
                    || event.getCause() == EntityDamageEvent.DamageCause.POISON))) {
            event.setDamage(-event.getDamage());
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnDamage(final EntityDamageEvent event) {
      
    }

    @EventHandler
    public void onMemberDamage(final EntityDamageByEntityEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getEntity().getWorld())) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player p2 = (Player) event.getEntity();
        if (event.getDamager() instanceof Player) {
            Player p1 = (Player) event.getDamager();
            cancelMemberDamage(p1, p2, event);
        } else if (event.getDamager() instanceof Projectile
                && !(event.getDamager() instanceof EnderPearl)) {
            ProjectileSource shooter = ((Projectile) event.getDamager()).getShooter();
            if (shooter instanceof Player) {
                Player p1 = (Player) shooter;
                cancelMemberDamage(p1, p2, event);
            }
        }
    }

    private void cancelMemberDamage(Player p1, Player p2, EntityDamageByEntityEvent event) {
        IslandInfo is1 = plugin.getIslandInfo(p1);
        IslandInfo is2 = plugin.getIslandInfo(p2);
        if (is1 != null && is2 != null && is1.getName().equals(is2.getName())) {
            plugin.notifyPlayer(p1, tr("\u00a7eYou cannot hurt island-members."));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (Settings.extras_sendToSpawn) {
            return;
        }
        if (plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            event.setRespawnLocation(plugin.getWorldManager().getWorld().getSpawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        //test nether
        if (event.getTo().getWorld().getName().equals("world_skyland_nether")){
            if(plugin.getPlayerInfo(event.getPlayer()).checkChallenge("builder5")==0){
                event.setCancelled(true);
                plugin.notifyPlayer(event.getPlayer(), (tr("\u00a7cYou do not have permission. Complete newbie challenges to unlock this command. ")));
                return;
            }
        }
        //test end
        if (event.getTo().getWorld().getName().equals("world_skyland_the_end")){
            if(plugin.getPlayerInfo(event.getPlayer()).checkChallenge("builder10")==0){
                event.setCancelled(true);
                plugin.notifyPlayer(event.getPlayer(), (tr("\u00a7cYou do not have permission. Complete adept challenges to unlock this command. ")));
                return;
            }
        }
        if (event.getTo() != null || !plugin.getWorldManager().isSkyWorld(event.getTo().getWorld())) {
            return;
        }
        final Player player = event.getPlayer();
        boolean isAdmin = player.isOp() || player.hasPermission("usb.mod.bypassprotection");
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(WorldGuardHandler.getIslandNameAt(event.getTo()));
        if (!isAdmin && islandInfo != null && islandInfo.isBanned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a74That player has forbidden you from teleporting to their island."));
        }
        if (!isAdmin && islandInfo != null && islandInfo.isLocked() && !islandInfo.getMembers().contains(player.getName()) && !islandInfo.isTrusted(player)) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a74That island is \u00a7clocked.\u00a7e No teleporting to the island."));
        }
        if (!event.isCancelled()) {
            final PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            playerInfo.onTeleport(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeafBreak(BlockBreakEvent event) {
        if (plugin.getWorldManager().isSkyGrid(event.getPlayer().getWorld())&&event.getBlock().getType() == Material.OAK_LEAVES){
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), new ItemStack(Material.OAK_SAPLING, 1));
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            return;
        }
        if (event.getBlock().getType() != Material.OAK_LEAVES || (event.getBlock().getData() & 0x3) != 0) {
            return;
        }
        // Ok, a player broke an OAK LEAF in the Skyworld
        String islandName = WorldGuardHandler.getIslandNameAt(event.getPlayer().getLocation());
        IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        if (islandInfo != null && islandInfo.getLeafBreaks() == 0) {
            // Add an oak-sapling
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), new ItemStack(Material.OAK_SAPLING, 1));
            islandInfo.setLeafBreaks(islandInfo.getLeafBreaks() + 1);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event){
        if(plugin.getWorldManager().isSkyGrid(event.getPlayer().getWorld())){
            if(plugin.getConfig().getInt("skygrid.regen",0)==1){
                event.getPlayer().sendMessage(tr("\u00a7cSkygrid is regenerating, you have been teleported to spawn."));
                uSkyBlock.getInstance().getTeleportLogic().spawnTeleport(event.getPlayer(), true);
            }
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onPlaceEnderEye(PlayerInteractEvent event){
        final Player player = event.getPlayer();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return; // Skip
        }
        if (event.getClickedBlock().getType() != Material.END_PORTAL_FRAME){
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE){
            return;
        }
        IslandInfo isi = plugin.getIslandInfo(player.getLocation());
        if (isi == null){
            event.setCancelled(true);
            player.sendMessage(tr("You cannot place {0} before complete {1}", "末影之眼", "准备沙漠神殿材料"));
            return;
        }
        PlayerInfo playerInfo = plugin.getPlayerInfo(isi.getLeaderUniqueId());
        if (playerInfo == null){
            event.setCancelled(true);
            player.sendMessage(tr("You cannot place {0} before complete {1}", "末影之眼", "准备沙漠神殿材料"));
            return;
        }
        int times = playerInfo.checkChallenge("desserttemple");
        if (times == 0){
            event.setCancelled(true);
            player.sendMessage(tr("You cannot place {0} before complete {1}", "末影之眼", "准备沙漠神殿材料"));
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlaceEvent(BlockPlaceEvent event)
    {
        final Player player = event.getPlayer();
        if (!blockLimitsEnabled || !plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return; // Skip
        }

        IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
        if (islandInfo == null) {
            return;
        }
        Material type = event.getBlock().getType();
        
        Map<Material, String> regulation;
        regulation = new HashMap<Material,String>();
        regulation.put(Material.OBSIDIAN, "builder5");
        regulation.put(Material.CHORUS_FLOWER, "builder10");

        for (Material m : regulation.keySet()){
            if(type == m){
                String name = plugin.getChallengeLogic().getChallenge(regulation.get(m)).getDisplayName();
                IslandInfo isi = plugin.getIslandInfo(player.getLocation());
                if (isi == null){
                    event.setCancelled(true);
                    player.sendMessage(tr("You cannot place {0} before complete {1}", LanguageHelper.getItemDisplayName(new ItemStack(m), "zh_cn"), name));
                    return;
                }
                PlayerInfo playerInfo = plugin.getPlayerInfo(isi.getLeaderUniqueId());
                if (playerInfo == null){
                    event.setCancelled(true);
                    player.sendMessage(tr("You cannot place {0} before complete {1}", LanguageHelper.getItemDisplayName(new ItemStack(m), "zh_cn"), name));
                    return;
                }
                ChallengeCompletion cc = playerInfo.getChallenge(regulation.get(m));
                if(cc.getTimesCompleted() == 0){
                    event.setCancelled(true);
                    player.sendMessage(tr("You cannot place {0} before complete {1}", LanguageHelper.getItemDisplayName(new ItemStack(m), "zh_cn"), name));
                }
            }
        }
        BlockLimitLogic.CanPlace canPlace = plugin.getBlockLimitLogic().canPlace(type, islandInfo);
        if (canPlace == BlockLimitLogic.CanPlace.UNCERTAIN) {
            event.setCancelled(true);
            final String key = "usb.block-limits";
            if (!PatienceTester.isRunning(player, key)) {
                PatienceTester.startRunning(player, key);
                player.sendMessage(tr("\u00a74{0} is limited. \u00a7eScanning your island to see if you are allowed to place more, please be patient", LanguageHelper.getItemDisplayName(new ItemStack(type), "zh_cn")));
                plugin.fireAsyncEvent(new IslandInfoEvent(player, islandInfo.getIslandLocation(), new Callback<IslandScore>() {
                    @Override
                    public void run() {
                        player.sendMessage(tr("\u00a7e... Scanning complete, you can try again"));
                        PatienceTester.stopRunning(player, key);
                    }
                }));
            }
            return;
        }
        if (canPlace == BlockLimitLogic.CanPlace.NO) {
            event.setCancelled(true);
            if (type == Material.HOPPER)
                player.sendMessage(tr("\u00a74You''ve hit the {0} limit!\u00a7e You can''t have more of that type on your island!\u00a79 Max: {1,number}", LanguageHelper.getItemDisplayName(new ItemStack(type), "zh_cn"), (plugin.getBlockLimitLogic().getLimit(type)+islandInfo.getHopperLimit())));
            else
                player.sendMessage(tr("\u00a74You''ve hit the {0} limit!\u00a7e You can''t have more of that type on your island!\u00a79 Max: {1,number}", LanguageHelper.getItemDisplayName(new ItemStack(type), "zh_cn"), plugin.getBlockLimitLogic().getLimit(type)));
            return;
        }
        plugin.getBlockLimitLogic().incBlockCount(islandInfo.getIslandLocation(), type);
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onHopperDestroy(BlockBreakEvent event){
        if (!blockLimitsEnabled || !plugin.getWorldManager().isSkyAssociatedWorld(event.getPlayer().getWorld())) {
            return; // Skip
        }
        IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
        if (islandInfo == null) {
            return;
        }
        plugin.getBlockLimitLogic().decBlockCount(islandInfo.getIslandLocation(), event.getBlock().getType());
    }
}
