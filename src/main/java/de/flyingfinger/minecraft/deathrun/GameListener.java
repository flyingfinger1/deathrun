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
 * Bukkit-Event-Listener für alle spielrelevanten Ereignisse.
 * Delegiert die Spiellogik an den {@link GameManager} und setzt
 * game-spezifische Einschränkungen (Pause, Block-Schutz, Login-Sperre) durch.
 */
public class GameListener implements Listener {

    private final GameManager gm;
    private final DeathrunPlugin plugin;

    /**
     * @param gm     der zentrale GameManager
     * @param plugin die Plugin-Instanz (für verzögerte Tasks)
     */
    public GameListener(GameManager gm, DeathrunPlugin plugin) {
        this.gm = gm;
        this.plugin = plugin;
    }

    // ── Tod ───────────────────────────────────────────────────────────────────

    /** Behandelt den Tod eines Spielers im laufenden Rennen. Unterdrückt die Standard-Todesmeldung. */
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

    /** Setzt den Respawn-Punkt auf den Spawn und übergibt den Spieler im nächsten Tick in den Spektatormodus. */
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

    /** Verhindert natürliche Heilung für lebende Spieler während des Rennens. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealthRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (gm.getState() != GameState.RUNNING) return;
        if (!gm.isInGame(player)) return;
        PlayerData pd = gm.getPlayers().get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        event.setCancelled(true); // Kein Heilen während des Rennens
    }

    /** Hält die Sättigung bei 0, damit der Regen-Mechanismus nicht durch Essen ausgelöst wird. */
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

    // ── Block-Schutz ──────────────────────────────────────────────────────────

    /** Schützt Käfigblöcke vor dem Abbauen; sperrt Blockinteraktion während der Pause. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Käfigblöcke immer schützen (für alle Spieler, jederzeit)
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
            return;
        }
        // Im Pause-Mode: In-Game-Spieler dürfen keine Blöcke abbauen
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Schützt Käfigblöcke vor dem Platzieren; sperrt Blockinteraktion während der Pause. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Käfigblöcke immer schützen (für alle Spieler, jederzeit)
        if (gm.getCageLocations().contains(event.getBlock().getLocation().toBlockLocation())) {
            event.setCancelled(true);
            return;
        }
        // Im Pause-Mode: In-Game-Spieler dürfen keine Blöcke setzen
        if (gm.isPaused() && gm.isInGame(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ── Pause: Inventar, Essen, Craften gesperrt ──────────────────────────────

    /** Sperrt Inventar-Klicks für In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    /** Sperrt Inventar-Drag für In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    /** Verhindert das Konsumieren von Items (Tränken, Nahrung) während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // Kein Item-Benutzen (Ender-Perlen, Angelrute, Tränke, Eimer …)
    /** Verhindert Rechtsklick-Aktionen (Item-Benutzung) für In-Game-Spieler während der Pause. */
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
    /** Verhindert den Abschuss von Projektilen durch In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // Kein Item-Droppen
    /** Verhindert das Wegwerfen von Items durch In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!gm.isPaused()) return;
        if (!gm.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
    }

    // Kein Item-Aufheben
    /** Verhindert das Aufheben von Items durch In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // Kein Boot/Lore betreten (umgeht den Bewegungs-Freeze)
    /** Verhindert das Betreten von Fahrzeugen (Boot, Lore) während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getEntered() instanceof Player player)) return;
        if (!gm.isInGame(player)) return;
        event.setCancelled(true);
    }

    // ── Pause: Bewegung einfrieren ────────────────────────────────────────────

    /** Friert die horizontale Bewegung lebender In-Game-Spieler während der Pause ein; vertikales Fallen bleibt erlaubt. */
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

    /** Unterdrückt Schaden nach Spielende sowie für In-Game-Spieler während der Pause. */
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

    /** Deaktiviert Mob-Targeting auf Spieler nach Spielende sowie während der Pause. */
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

    /** Verhindert Angriffe durch In-Game-Spieler während der Pause. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttackDuringPause(EntityDamageByEntityEvent event) {
        if (!gm.isPaused()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── PVP-Kontrolle (gilt immer während des Rennens) ────────────────────────

    /** Blockiert PVP zwischen In-Game-Spielern solange der PVP-Delay noch nicht abgelaufen ist. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (gm.getState() != GameState.RUNNING) return;
        if (gm.isPvpActive()) return; // PVP aktiv (Delay abgelaufen, nicht pausiert) → Schaden erlaubt
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (!gm.isInGame(attacker)) return;
        event.setCancelled(true);
    }

    // ── Login-Sperre ──────────────────────────────────────────────────────────

    /**
     * Prüft beim Login, ob der Spieler in der aktuellen Spielphase zugelassen ist.
     * Admins (OP oder {@code deathrun.admin}) können immer beitreten.
     */
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

    /** Delegiert den Beitritt eines Spielers im nächsten Tick an den GameManager. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Im nächsten Tick ausführen, damit der Spieler vollständig geladen ist
        event.getPlayer().getServer().getScheduler().runTask(plugin, () ->
            gm.handleJoin(event.getPlayer()));
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    /** Delegiert das Verlassen eines Spielers an den GameManager. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gm.handleQuit(event.getPlayer());
    }
}
