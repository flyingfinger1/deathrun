package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

/**
 * Bukkit event listener for all game-relevant events.
 * Delegates game logic to the {@link GameManager} and enforces
 * game-specific restrictions (pause, block protection, login lock).
 */
public class GameListener implements Listener {

    private final GameManager gm;
    private final DeathrunPlugin plugin;

    /**
     * @param gm     the central GameManager
     * @param plugin the plugin instance (for deferred tasks)
     */
    public GameListener(GameManager gm, DeathrunPlugin plugin) {
        this.gm = gm;
        this.plugin = plugin;
    }

    // ── Death ───────────────────────────────────────────────────────────────────

    /** Handles the death of a player during the running race. Suppresses the default death message. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;

        // Remember death position before respawn happens
        gm.handleDeath(player, player.getLocation());

        // Suppress default death message (we send our own)
        event.setCancelled(false); // Player dies normally (for respawn)
        event.deathMessage(null);  // No default death message
    }

    /** Sets the respawn point to spawn and transitions the player to spectator mode in the next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!gm.isInGame(player)) return;

        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd != null && !pd.isAlive()) {
            // Set respawn point to start location, then switch to spectator
            if (gm.getSpawnLocation() != null) {
                event.setRespawnLocation(gm.getSpawnLocation());
            }
            // Set spectator mode in the next tick (after respawn)
            player.getServer().getScheduler().runTask(plugin, () -> gm.handleRespawn(player));
        }
    }

    // ── Disable regeneration ────────────────────────────────────────────────────

    /** Prevents natural health regeneration for living players during the race. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealthRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        event.setCancelled(true); // No healing during the race
    }

    /** Keeps saturation at 0 so the regeneration mechanic cannot be triggered by eating. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        // Eating allowed, but keep saturation at 0 → no indirect healing
        event.setFoodLevel(Math.min(event.getFoodLevel(), 20));
        // Set saturation to 0 so the regeneration mechanic does not trigger
        player.setSaturation(0f);
    }

    // ── Block protection ──────────────────────────────────────────────────────────

    /** Protects cage blocks from being broken; locks block interaction during pause. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Always protect cage blocks (for all players, at all times)
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
            return;
        }
        // In pause mode: in-game players may not break blocks
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Protects cage blocks from being placed on; locks block interaction during pause. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Always protect cage blocks (for all players, at all times)
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
            return;
        }
        // In pause mode: in-game players may not place blocks
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ── Pause: inventory, eating, crafting locked ──────────────────────────────

    /** Locks inventory clicks for in-game players during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    /** Locks inventory drag for in-game players during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    /** Prevents consuming items (potions, food) during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // No item use (ender pearls, fishing rods, potions, buckets …)
    /** Prevents right-click actions (item use) for in-game players during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        var action = event.getAction();
        if (action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
         || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }
    }

    // No projectiles (arrows, snowballs, tridents …)
    /** Prevents in-game players from launching projectiles during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // No item dropping
    /** Prevents in-game players from dropping items during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // No item picking up
    /** Prevents in-game players from picking up items during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // No entering boats/minecarts (bypasses the movement freeze)
    /** Prevents entering vehicles (boat, minecart) during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntered() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Pause: freeze movement ────────────────────────────────────────────

    /** Freezes the horizontal movement of living in-game players during pause; vertical falling remains allowed. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!gm.isPaused()) return;
        Player player = event.getPlayer();
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        // Allow Y movement (falling), block X/Z
        var from = event.getFrom();
        var to   = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
            var fixed = to.clone();
            fixed.setX(from.getX());
            fixed.setZ(from.getZ());
            event.setTo(fixed);
        }
    }

    // ── No damage after game end + pause ───────────────────────────────────

    /** Suppresses damage after game end and for in-game players during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // After game end: no damage for players
        if (gm.getState() == GameState.ENDED && event.getEntity() instanceof Player) {
            event.setCancelled(true); return;
        }
        if (!gm.isPaused()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Mobs do not attack after game end + pause ──────────────────────────

    /** Disables mob targeting of players after game end and during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        // After game end: disable mobs targeting players
        if (gm.getState() == GameState.ENDED && event.getTarget() instanceof Player) {
            event.setCancelled(true); return;
        }
        if (!gm.isPaused()) return;
        if (!(event.getTarget() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Pause: game participants may not attack ───────────────────────

    /** Prevents attacks by in-game players during pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttackDuringPause(EntityDamageByEntityEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── PVP control (always active during the race) ────────────────────────

    /** Blocks PVP between in-game players as long as the PVP delay has not yet expired. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (gm.getState() != GameState.RUNNING) return;
        if (gm.isPvpActive()) return; // PVP active (delay expired, not paused) → damage allowed
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── Login lock ──────────────────────────────────────────────────────────

    /**
     * Checks at login whether the player is permitted in the current game phase.
     * Admins (OP or {@code deathrun.admin}) can always join.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        boolean isAdmin = event.getPlayer().isOp()
            || event.getPlayer().hasPermission("deathrun.admin");
        GameState state = gm.getState();

        // Server locked (maintenance mode / setup): admins only
        if (!gm.isServerOpen() && (state == GameState.IDLE || state == GameState.ENDED)) {
            if (!isAdmin) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Messages.comp(event.getPlayer(), "login.maintenance"));
            }
            return;
        }

        // Game running: only known players or admins
        if (state == GameState.STARTING || state == GameState.RUNNING) {
            if (!isAdmin && !gm.getPlayers().containsKey(event.getPlayer().getUniqueId())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Messages.comp(event.getPlayer(), "login.game-running"));
            }
        }
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    /** Delegates a player's join to the GameManager in the next tick. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Execute in the next tick so the player is fully loaded
        event.getPlayer().getServer().getScheduler().runTask(plugin, () ->
            gm.handleJoin(event.getPlayer()));
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    /** Delegates a player's departure to the GameManager. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gm.handleQuit(event.getPlayer());
    }
}
