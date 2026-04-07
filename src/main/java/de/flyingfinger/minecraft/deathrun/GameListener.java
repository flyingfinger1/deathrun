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

public class GameListener implements Listener {

    private final GameManager gm;
    private final DeathrunPlugin plugin;

    public GameListener(GameManager gm, DeathrunPlugin plugin) {
        this.gm = gm;
        this.plugin = plugin;
    }

    // ── Tod ───────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;

        // Todesposition merken, bevor Respawn passiert
        gm.handleDeath(player, player.getLocation());

        // Standard-Todesmeldung unterdrücken (wir senden eigene)
        event.setCancelled(false); // Spieler stirbt normal (für Respawn)
        event.deathMessage(null);  // Keine Standard-Todesmeldung
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!gm.isInGame(player)) return;

        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd != null && !pd.isAlive()) {
            // Respawn-Punkt auf Startlocation setzen, dann Spektator
            if (gm.getSpawnLocation() != null) {
                event.setRespawnLocation(gm.getSpawnLocation());
            }
            // Spektatormodus im nächsten Tick setzen (nach Respawn)
            player.getServer().getScheduler().runTask(plugin, () -> gm.handleRespawn(player));
        }
    }

    // ── Regen deaktivieren ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealthRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        event.setCancelled(true); // Kein Heilen während des Rennens
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        // Essen erlaubt, aber Sättigung auf 0 halten → kein indirektes Heilen
        event.setFoodLevel(Math.min(event.getFoodLevel(), 20));
        // Sättigung auf 0 setzen damit der Regen-Mechanismus nicht triggert
        player.setSaturation(0f);
    }

    // ── Käfig schützen + Pause: kein Block-Abbau/-Platzierung ────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) { event.setCancelled(true); return; }
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) { event.setCancelled(true); return; }
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
        }
    }

    // ── Pause: Inventar, Essen, Craften gesperrt ──────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // Kein Item-Benutzen (Ender-Perlen, Angelrute, Tränke, Eimer …)
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

    // Keine Projektile (Pfeile, Schneebälle, Dreizack …)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // Kein Item-Droppen
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // Kein Item-Aufheben
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // Kein Boot/Lore betreten (umgeht den Bewegungs-Freeze)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntered() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Pause: Bewegung einfrieren ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!gm.isPaused()) return;
        Player player = event.getPlayer();
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        // Y-Bewegung (Fallen) erlauben, X/Z blockieren
        var from = event.getFrom();
        var to   = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
            var fixed = to.clone();
            fixed.setX(from.getX());
            fixed.setZ(from.getZ());
            event.setTo(fixed);
        }
    }

    // ── Kein Schaden nach Spielende + Pause ───────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // Nach Spielende: kein Schaden für Spieler
        if (gm.getState() == GameState.ENDED && event.getEntity() instanceof Player) {
            event.setCancelled(true); return;
        }
        if (!gm.isPaused()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Mobs greifen nach Spielende nicht an + Pause ──────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        // Nach Spielende: Mobs targeting Spieler deaktivieren
        if (gm.getState() == GameState.ENDED && event.getTarget() instanceof Player) {
            event.setCancelled(true); return;
        }
        if (!gm.isPaused()) return;
        if (!(event.getTarget() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Pause: Spielteilnehmer dürfen nichts angreifen ───────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttackDuringPause(EntityDamageByEntityEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── PVP-Kontrolle (gilt immer während des Rennens) ────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (gm.getState() != GameState.RUNNING) return;
        if (gm.isPvpEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── Login-Sperre ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        boolean isAdmin = event.getPlayer().isOp()
            || event.getPlayer().hasPermission("deathrun.admin");
        GameState state = gm.getState();

        // Server gesperrt (Wartungsmodus / Setup): nur Admins
        if (!gm.isServerOpen() && (state == GameState.IDLE || state == GameState.ENDED)) {
            if (!isAdmin) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Messages.comp(event.getPlayer(), "login.maintenance"));
            }
            return;
        }

        // Spiel läuft: nur bekannte Spieler oder Admins
        if (state == GameState.STARTING || state == GameState.RUNNING) {
            if (!isAdmin && !gm.getPlayers().containsKey(event.getPlayer().getUniqueId())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Messages.comp(event.getPlayer(), "login.game-running"));
            }
        }
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Im nächsten Tick ausführen, damit der Spieler vollständig geladen ist
        event.getPlayer().getServer().getScheduler().runTask(plugin, () ->
            gm.handleJoin(event.getPlayer()));
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gm.handleQuit(event.getPlayer());
    }
}
