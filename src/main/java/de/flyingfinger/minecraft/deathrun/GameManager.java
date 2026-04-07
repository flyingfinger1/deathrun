package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central controller class of the Deathrun plugin.
 * Manages the game state, coordinates all subsystems (cage, sidebar, tablist)
 * and provides the public API for commands and event listeners.
 */
public class GameManager {

    private final DeathrunPlugin plugin;

    // ── Configuration ─────────────────────────────────────────────────────────
    private Location startLocation;  // measurement point: outer edge of the cage wall
    private Location spawnLocation;  // player spawn: center of the cage
    private RunDirection direction = RunDirection.NORTH;
    private int corridorWidth = 30;
    private int countdownSeconds = 10;
    private String serverName = "DeathRun";
    private int cageRadius = 3;
    private double  borderDamagePerBlock = 0.5;
    private double  borderDamageBuffer   = 0.0;
    private boolean pvpEnabled           = false;
    private boolean pvpActive            = false; // runtime state: PVP currently active?
    private int     pvpDelaySeconds      = 0;     // seconds until PVP is activated
    private int     pvpCountdownSeconds  = 5;     // seconds of individual countdown before that
    private int     maxTimeSeconds       = 0;

    // ── Game state ──────────────────────────────────────────────────────────────
    private GameState state  = GameState.IDLE;
    private boolean   paused = false;
    private long raceStartTime  = 0;   // ms when race begins
    private long totalPausedMs  = 0;   // accumulated pause time
    private long pauseStartMs   = 0;   // ms when current pause started
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private Set<Location> cageLocations = new HashSet<>();

    // ── Server state ────────────────────────────────────────────────────────────
    private boolean serverOpen    = false;  // false = only OPs may join
    private Location winnerLocation = null; // last known location of the winner
    private List<PlayerData> finalResults = List.of(); // result after game end

    // ── Tasks ─────────────────────────────────────────────────────────────────
    private BukkitTask countdownTask;
    private BukkitTask borderTask;
    private BukkitTask updateTask;
    private BukkitTask actionBarTask;
    private BukkitTask lobbyTask;
    private BukkitTask pvpDelayTask;

    // ── Managers ──────────────────────────────────────────────────────────────
    private final CageBuilder cageBuilder = new CageBuilder();
    private final SidebarManager sidebar = new SidebarManager();
    private final TablistManager tablist = new TablistManager();

    /**
     * Creates the GameManager and loads the persisted configuration.
     * @param plugin the associated plugin instance
     */
    public GameManager(DeathrunPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    // ── Load configuration ───────────────────────────────────────────────────

    /** Reads all plugin settings from config.yml and restores saved positions. */
    private void loadConfig() {
        var cfg = plugin.getConfig();
        corridorWidth = cfg.getInt("corridor-width", 30);
        countdownSeconds = cfg.getInt("countdown", 10);
        serverName = cfg.getString("server-name", "DeathRun");
        cageRadius = cfg.getInt("cage.radius", 3);
        try {
            direction = RunDirection.valueOf(cfg.getString("direction", "NORTH").toUpperCase());
        } catch (IllegalArgumentException ignored) {}
        borderDamagePerBlock = cfg.getDouble("border-damage-per-block", 0.5);
        borderDamageBuffer   = cfg.getDouble("border-damage-buffer", 0.0);
        pvpEnabled          = cfg.getBoolean("pvp", false);
        pvpDelaySeconds     = cfg.getInt("pvp-delay", 0);
        pvpCountdownSeconds = cfg.getInt("pvp-countdown", 5);
        maxTimeSeconds      = cfg.getInt("max-time", 0);

        String worldName = cfg.getString("start.world", "");
        if (!worldName.isEmpty()) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                startLocation = new Location(w,
                    cfg.getDouble("start.x"),
                    cfg.getDouble("start.y"),
                    cfg.getDouble("start.z"));
            }
        }
        String spawnWorld = cfg.getString("spawn.world", "");
        if (!spawnWorld.isEmpty()) {
            World w = Bukkit.getWorld(spawnWorld);
            if (w != null) {
                spawnLocation = new Location(w,
                    cfg.getDouble("spawn.x"),
                    cfg.getDouble("spawn.y"),
                    cfg.getDouble("spawn.z"),
                    (float) cfg.getDouble("spawn.yaw", 0.0), 0f);
            }
        }

        // Restore cage state after restart (blocks still exist in the world)
        if (cfg.getBoolean("cage.built", false) && spawnLocation != null) {
            cageLocations = cageBuilder.restoreState(spawnLocation, cageRadius, direction);
        }
    }

    // ── Admin commands ─────────────────────────────────────────────────────────

    /**
     * Builds the start cage at the admin's current position,
     * saves spawn and start locations, and persists them in the configuration.
     * @param sender the admin who executed the command
     */
    public void buildCage(Player sender) {
        // Cage center = current player position (block center)
        Location center = sender.getLocation().clone();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int r  = CageBuilder.getEffectiveRadius(cageRadius);

        // Build cage
        cageLocations = cageBuilder.buildCage(center, cageRadius, direction);

        // Spawn = center of the cage, facing direction = run direction
        spawnLocation = new Location(center.getWorld(),
            cx + 0.5, cy, cz + 0.5, spawnYaw(direction), 0f);

        // Measurement point = outer face of the indicator wall
        startLocation = spawnLocation.clone();
        switch (direction) {
            case NORTH -> startLocation.setZ(cz - r);
            case SOUTH -> startLocation.setZ(cz + r + 1);
            case EAST  -> startLocation.setX(cx + r + 1);
            case WEST  -> startLocation.setX(cx - r);
        }

        // Save config (including cage.built flag for restart persistence)
        saveLocCfg("start", startLocation);
        saveLocCfg("spawn", spawnLocation);
        plugin.getConfig().set("cage.built", true);
        plugin.saveConfig();

        // Set world spawn
        center.getWorld().setSpawnLocation(spawnLocation);

        sender.sendMessage(Messages.comp(sender, "cmd.buildcage.success", Messages.str(sender, direction.getLangKey())));
    }

    /**
     * Returns the facing direction (yaw) at which players should stand in the cage
     * so they are looking in the run direction.
     * @param dir run direction
     * @return yaw angle in degrees
     */
    private float spawnYaw(RunDirection dir) {
        return switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST  -> -90f;
            case WEST  -> 90f;
        };
    }

    /**
     * Writes a {@link Location} under the specified key prefix to config.yml.
     * @param key prefix (e.g. {@code "start"} or {@code "spawn"})
     * @param loc position to save
     */
    private void saveLocCfg(String key, Location loc) {
        var cfg = plugin.getConfig();
        cfg.set(key + ".world", loc.getWorld().getName());
        cfg.set(key + ".x", loc.getX());
        cfg.set(key + ".y", loc.getY());
        cfg.set(key + ".z", loc.getZ());
        if (loc.getYaw() != 0f) cfg.set(key + ".yaw", loc.getYaw());
    }

    /**
     * Removes the cage from the world and updates the configuration.
     * @param sender the admin who executed the command
     */
    public void removeCage(Player sender) {
        cageBuilder.removeCage(cageLocations);
        cageLocations.clear();
        plugin.getConfig().set("cage.built", false);
        plugin.saveConfig();
        sender.sendMessage(Messages.comp(sender, "cmd.removecage.success"));
    }

    /**
     * Changes the corridor width (half WorldBorder size).
     * @param sender command sender for feedback
     * @param width  new width in blocks (5–500)
     */
    public void setCorridorWidth(CommandSender sender, int width) {
        if (width < 5 || width > 500) {
            sender.sendMessage(Messages.comp(sender, "cmd.setcorridor.invalid"));
            return;
        }
        corridorWidth = width;
        plugin.getConfig().set("corridor-width", width);
        plugin.saveConfig();
        sender.sendMessage(Messages.comp(sender, "cmd.setcorridor.success", width));
    }

    /**
     * Changes the run direction. If the cage is already built, the indicator wall
     * and measurement point are updated immediately.
     * @param sender command sender for feedback
     * @param arg    direction name (NORTH, SOUTH, EAST, WEST; case-insensitive)
     */
    public void setDirection(CommandSender sender, String arg) {
        try {
            direction = RunDirection.valueOf(arg.toUpperCase());
            plugin.getConfig().set("direction", direction.name());
            plugin.saveConfig();
            sender.sendMessage(Messages.comp(sender, "cmd.setdirection.success", Messages.str(sender, direction.getLangKey())));

            // If cage is already built: rebuild door to new side & update measurement point
            if (!cageLocations.isEmpty() && spawnLocation != null) {
                int cx = spawnLocation.getBlockX();
                int cy = spawnLocation.getBlockY();
                int cz = spawnLocation.getBlockZ();
                int r  = CageBuilder.getEffectiveRadius(cageRadius);

                cageBuilder.changeDirection(cx, cy, cz, cageRadius, direction);

                // Measurement point = outer face of the new indicator wall
                startLocation = spawnLocation.clone();
                switch (direction) {
                    case NORTH -> startLocation.setZ(cz - r);
                    case SOUTH -> startLocation.setZ(cz + r + 1);
                    case EAST  -> startLocation.setX(cx + r + 1);
                    case WEST  -> startLocation.setX(cx - r);
                }
                saveLocCfg("start", startLocation);
                plugin.saveConfig();

                // Update spawn facing direction
                spawnLocation.setYaw(spawnYaw(direction));
                saveLocCfg("spawn", spawnLocation);
                plugin.saveConfig();
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Messages.comp(sender, "cmd.setdirection.invalid"));
        }
    }

    /**
     * Sets the time limit for the race.
     * @param sender  command sender for feedback
     * @param minutes maximum race duration in minutes (0 = unlimited)
     */
    public void setMaxTime(CommandSender sender, int minutes) {
        if (minutes < 0) {
            sender.sendMessage(Messages.comp(sender, "cmd.settime.invalid"));
            return;
        }
        maxTimeSeconds = minutes * 60;
        plugin.getConfig().set("max-time", maxTimeSeconds);
        plugin.saveConfig();
        String display = maxTimeSeconds == 0
            ? Messages.str(sender, "cmd.settime.unlimited")
            : Messages.str(sender, "cmd.settime.minutes", minutes);
        sender.sendMessage(Messages.comp(sender, "cmd.settime.success", display));
    }

    /**
     * Outputs a status overview of the current plugin configuration and game state.
     * @param sender recipient of the status message
     */
    public void showStatus(CommandSender sender) {
        sender.sendMessage(Messages.comp(sender, "cmd.status.header"));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.state"), state.name()));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.spawn"),
            spawnLocation == null ? Messages.str(sender, "cmd.status.not-set") : fmtLoc(spawnLocation)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.start"),
            startLocation == null ? Messages.str(sender, "cmd.status.not-set") : fmtLoc(startLocation)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.direction"), Messages.str(sender, direction.getLangKey())));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.corridor"),
            Messages.str(sender, "cmd.status.corridor-value", corridorWidth)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.countdown"),
            Messages.str(sender, "cmd.status.countdown-value", countdownSeconds)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.maxtime"),
            maxTimeSeconds == 0
                ? Messages.str(sender, "cmd.status.time-unlimited")
                : Messages.str(sender, "cmd.status.time-minutes", maxTimeSeconds / 60)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.online"),
            String.valueOf(Bukkit.getOnlinePlayers().size())));
    }

    // ── Game start ────────────────────────────────────────────────────────────

    /**
     * Starts the countdown and transitions to the {@link GameState#STARTING} state.
     * @param sender command sender for feedback
     * @return {@code true} if the start was successfully initiated
     */
    public boolean startGame(CommandSender sender) {
        if (state == GameState.STARTING || state == GameState.RUNNING) {
            sender.sendMessage(Messages.comp(sender, "cmd.start.already-running"));
            return false;
        }
        if (spawnLocation == null) {
            sender.sendMessage(Messages.comp(sender, "cmd.start.no-cage"));
            return false;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            sender.sendMessage(Messages.comp(sender, "cmd.start.no-players"));
            return false;
        }

        state = GameState.STARTING;
        players.clear();
        // Tear down lobby boards, stop lobby task
        if (lobbyTask != null) { lobbyTask.cancel(); lobbyTask = null; }
        sidebar.clearLobbyBoards();

        for (Player p : Bukkit.getOnlinePlayers()) {
            players.put(p.getUniqueId(), new PlayerData(p));
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(spawnLocation);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }

        broadcast("game.countdown.announce", countdownSeconds);

        // Show scoreboard already during countdown
        sidebar.setupStarting(players.values(), serverName);
        sidebar.updateStarting(players.values(), countdownSeconds);

        final int[] rem = {countdownSeconds};
        countdownTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.STARTING) { cancel(); return; }
                if (rem[0] <= 0) { cancel(); beginRace(); return; }
                if (rem[0] <= 5 || rem[0] % 10 == 0) {
                    broadcast("game.countdown.tick", rem[0]);
                }
                sidebar.updateStarting(players.values(), rem[0]);
                sound(Sound.BLOCK_NOTE_BLOCK_PLING, rem[0] <= 3 ? 2f : 1f);
                rem[0]--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
        return true;
    }

    /**
     * Called after the countdown expires. Opens the cage, assigns personal
     * WorldBorders, and starts the border, update, and (optional) PVP tasks.
     */
    private void beginRace() {
        state = GameState.RUNNING;
        raceStartTime = System.currentTimeMillis();
        totalPausedMs = 0;
        setDayCycle(true); // cycle only runs during an active race
        pvpActive = false;
        if (pvpEnabled) schedulePvpActivation();

        // Open cage door FIRST – independent of everything else
        cageBuilder.openCage();
        cageLocations.removeAll(cageBuilder.getLastRemovedLocations());

        double sx = startLocation.getX();
        double sz = startLocation.getZ();

        // Set WorldBorder per player
        for (PlayerData pd : players.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            assignBorder(pd, p, sx, sz);
        }

        // Initialize sidebar – in try-catch so an error here never blocks openCage
        try {
            sidebar.setup(players.values(), serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("Sidebar-Fehler beim Rennstart: " + e.getMessage());
        }

        // Update border every 2 ticks
        borderTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }
                for (PlayerData pd : players.values()) {
                    if (!pd.isAlive()) continue;
                    Player p = Bukkit.getPlayer(pd.getUuid());
                    if (p == null || pd.getPersonalBorder() == null) continue;
                    WorldBorder wb = pd.getPersonalBorder();
                    wb.setCenter(
                        direction.getBorderCenterX(sx, sz, p.getLocation().getX(), p.getLocation().getZ()),
                        direction.getBorderCenterZ(sx, sz, p.getLocation().getX(), p.getLocation().getZ())
                    );
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);

        // Scoreboard + tablist + time limit check every second
        updateTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }
                // Check time limit (not during pause)
                if (maxTimeSeconds > 0 && !paused && getElapsedSeconds() >= maxTimeSeconds) {
                    broadcast("game.time-limit");
                    endGame();
                    return;
                }
                List<PlayerData> sorted = getSorted();
                sidebar.update(sorted, players, GameManager.this);
                tablist.update(sorted, GameManager.this);
                checkEndCondition();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Send "GO!" message + start title to each player in their language
        Title.Times startTimes = Title.Times.times(
            Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500));
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            String dirName = Messages.str(p, direction.getLangKey());
            p.sendMessage(Messages.comp(p, "game.countdown.go", dirName));
            p.showTitle(Title.title(
                Messages.comp(p, "game.start.title"),
                Messages.comp(p, "game.start.subtitle", dirName),
                startTimes));
        }
        sound(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
    }

    // ── WorldBorder ───────────────────────────────────────────────────────────

    /**
     * Assigns a personal WorldBorder to a player (Paper API).
     * If the Paper API is unavailable, a warning is logged and no exception is thrown.
     * @param pd the affected player
     * @param p  the online instance of the player
     * @param sx X coordinate of the start point
     * @param sz Z coordinate of the start point
     */
    private void assignBorder(PlayerData pd, Player p, double sx, double sz) {
        try {
            // Requires Paper API (Player.setWorldBorder + Bukkit.createWorldBorder)
            WorldBorder wb = Bukkit.createWorldBorder();
            wb.setSize(corridorWidth * 2.0);
            wb.setCenter(
                direction.getBorderCenterX(sx, sz, p.getLocation().getX(), p.getLocation().getZ()),
                direction.getBorderCenterZ(sx, sz, p.getLocation().getX(), p.getLocation().getZ())
            );
            wb.setWarningDistance(10);
            wb.setWarningTime(0);
            wb.setDamageBuffer(borderDamageBuffer);
            wb.setDamageAmount(borderDamagePerBlock);
            p.setWorldBorder(wb);
            pd.setPersonalBorder(wb);
        } catch (Exception e) {
            plugin.getLogger().warning("WorldBorder per Spieler nicht verfügbar: " + e.getMessage());
        }
    }

    /**
     * Removes a player's personal WorldBorder (on death, disconnect, or game end).
     * @param pd the affected player
     */
    private void clearBorder(PlayerData pd) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p != null) {
            try { p.setWorldBorder(null); } catch (Exception ignored) {}
        }
        pd.setPersonalBorder(null);
    }

    // ── Death ───────────────────────────────────────────────────────────────────

    /** Called from GameListener when a player dies during the race. */
    public void handleDeath(Player player, Location deathLoc) {
        PlayerData pd = players.get(player.getUniqueId());
        if (pd == null || !pd.isAlive()) return;

        double dist = direction.getForwardDistance(
            startLocation.getX(), startLocation.getZ(),
            deathLoc.getX(), deathLoc.getZ()
        );
        pd.setFinalDistance(Math.max(0, dist));
        pd.setAlive(false);
        clearBorder(pd);

        broadcast("game.death", player.getName(), fmt(dist));

        checkEndCondition();
    }

    /** After respawn: set player to spectator mode. */
    public void handleRespawn(Player player) {
        PlayerData pd = players.get(player.getUniqueId());
        if (pd == null || pd.isAlive()) return;
        player.setGameMode(GameMode.SPECTATOR);
    }

    // ── End check ──────────────────────────────────────────────────────────────

    /**
     * Checks whether the game-end condition is met (all players dead or
     * the last survivor has passed all others) and calls {@link #endGame()} if so.
     */
    private void checkEndCondition() {
        if (state != GameState.RUNNING) return;

        // All living players (including offline)
        List<PlayerData> aliveAll = players.values().stream()
            .filter(PlayerData::isAlive).collect(Collectors.toList());

        if (aliveAll.isEmpty()) { endGame(); return; }

        // Online living players only
        List<PlayerData> aliveOnline = aliveAll.stream()
            .filter(pd -> Bukkit.getPlayer(pd.getUuid()) != null)
            .collect(Collectors.toList());

        // All living players are currently offline → wait to see if they come back
        if (aliveOnline.isEmpty()) return;

        boolean anyDead = players.values().stream().anyMatch(p -> !p.isAlive());

        // Last survivor (no others alive, regardless of online/offline status)
        if (aliveAll.size() == 1 && anyDead) {
            Player last = Bukkit.getPlayer(aliveAll.get(0).getUuid());
            if (last == null) return; // disconnected – wait

            double lastDist = direction.getForwardDistance(
                startLocation.getX(), startLocation.getZ(),
                last.getLocation().getX(), last.getLocation().getZ()
            );
            double maxDeadDist = players.values().stream()
                .filter(p -> !p.isAlive())
                .mapToDouble(PlayerData::getFinalDistance)
                .max().orElse(0);

            if (lastDist > maxDeadDist) {
                aliveAll.get(0).setFinalDistance(lastDist);
                aliveAll.get(0).setAlive(false);
                clearBorder(aliveAll.get(0));
                endGame();
            }
        }
    }

    // ── Game end ──────────────────────────────────────────────────────────────

    /**
     * Ends the race, displays results, starts the lobby task,
     * and transitions to {@link GameState#ENDED}.
     */
    private void endGame() {
        if (state == GameState.ENDED) return;
        state = GameState.ENDED;

        cancelTasks();
        pvpActive = false;
        tablist.reset(players.values());

        finalResults = getSorted();

        // Remember winner position (before game mode is changed)
        winnerLocation = null;
        if (!finalResults.isEmpty()) {
            Player winner = Bukkit.getPlayer(finalResults.get(0).getUuid());
            if (winner != null) winnerLocation = winner.getLocation().clone();
        }

        // Return all players to survival
        for (PlayerData pd : players.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.setGameMode(GameMode.SURVIVAL);
        }

        // Title + fireworks sound
        String winnerName = finalResults.isEmpty() ? "" : finalResults.get(0).getName();
        String winnerDist = finalResults.isEmpty() ? "0" : fmt(finalResults.get(0).getFinalDistance());
        titleAllLong("game.end.title", "game.end.subtitle", winnerName, winnerDist);
        sound(Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f);

        // Results in chat – each player receives in their language
        broadcast("game.end.header");
        for (int i = 0; i < finalResults.size(); i++) {
            PlayerData pd = finalResults.get(i);
            String col = i == 0 ? "§6" : i == 1 ? "§e" : i == 2 ? "§a" : "§7";
            final int rank = i + 1;
            final String name = pd.getName();
            final String dist = fmt(pd.getFinalDistance());
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(Messages.comp(p, "game.end.entry", col, rank, name, dist));
            }
        }

        // Clickable teleport message (when winner position is known)
        if (winnerLocation != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Component gotoMsg = Messages.comp(p, "game.end.goto-prefix")
                    .append(Messages.comp(p, "game.end.goto-link")
                        .clickEvent(ClickEvent.runCommand("/dr goto"))
                        .hoverEvent(HoverEvent.showText(Messages.comp(p, "game.end.goto-hover"))));
                p.sendMessage(gotoMsg);
            }
        }

        // Stop day-night cycle & build end scoreboard
        setDayCycle(false);
        sidebar.setupEnded(finalResults, players, serverName);
        startLobbyTask();
    }

    /**
     * Toggles the game pause. In the paused state players are frozen
     * and an action bar display is started.
     * @param sender command sender for feedback
     */
    public void togglePause(CommandSender sender) {
        if (state != GameState.RUNNING) {
            sender.sendMessage(Messages.comp(sender, "cmd.pause.not-running"));
            return;
        }
        paused = !paused;
        if (paused) {
            pauseStartMs = System.currentTimeMillis();
            setDayCycle(false);
            broadcast("game.pause.paused");
            // Show action bar every 0.5s while paused
            actionBarTask = new BukkitRunnable() {
                @Override public void run() {
                    if (!paused) { cancel(); actionBarTask = null; return; }
                    for (UUID uuid : players.keySet()) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) p.sendActionBar(Messages.comp(p, "game.pause.actionbar"));
                    }
                }
            }.runTaskTimer(plugin, 0L, 10L);
        } else {
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            setDayCycle(true);
            if (actionBarTask != null) { actionBarTask.cancel(); actionBarTask = null; }
            // Clear action bar
            for (UUID uuid : players.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendActionBar(Component.empty());
            }
            broadcast("game.pause.resumed");
        }
    }

    /**
     * Aborts a running or started game.
     * @param sender command sender for feedback
     */
    public void stopGame(CommandSender sender) {
        if (state == GameState.IDLE) {
            sender.sendMessage(Messages.comp(sender, "cmd.stop.no-game"));
            return;
        }
        forceStop();
        sender.sendMessage(Messages.comp(sender, "cmd.stop.success"));
    }

    /**
     * Resets the plugin to its initial state (IDLE).
     * Called both on {@code /dr stop} and when the plugin is disabled.
     */
    public void forceStop() {
        // Stop day-night cycle (IDLE/ENDED = always frozen)
        setDayCycle(false);
        paused = false;
        pvpActive = false;
        if (lobbyTask != null) { lobbyTask.cancel(); lobbyTask = null; }
        cancelTasks();
        for (PlayerData pd : players.values()) {
            clearBorder(pd);
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.setGameMode(GameMode.SURVIVAL);
        }
        sidebar.removeAll(players.values());
        sidebar.clearLobbyBoards();
        tablist.reset(players.values());
        players.clear();
        finalResults = List.of();
        winnerLocation = null;
        state = GameState.IDLE;
        // Close cage door again
        cageBuilder.closeCage();
        cageLocations.addAll(cageBuilder.getLastRemovedLocations());
        startLobbyTask();
    }

    // ── Open / close server ─────────────────────────────────────────────────────

    /**
     * Opens the server for all players and sends a broadcast message.
     * @param sender command sender for feedback
     */
    public void openServer(CommandSender sender) {
        if (spawnLocation == null) {
            sender.sendMessage(Messages.comp(sender, "cmd.open.no-cage"));
            return;
        }
        serverOpen = true;
        sender.sendMessage(Messages.comp(sender, "cmd.open.success"));
        broadcast("cmd.open.broadcast");
    }

    /**
     * Closes the server to non-admins (maintenance mode).
     * @param sender command sender for feedback
     */
    public void closeServer(CommandSender sender) {
        serverOpen = false;
        sender.sendMessage(Messages.comp(sender, "cmd.close.success"));
    }

    /**
     * Teleports the player to the last known position of the winner.
     * Only available in the {@link GameState#ENDED} state.
     * @param player the player who executed the command
     */
    public void handleGoto(Player player) {
        if (state != GameState.ENDED || finalResults.isEmpty()) {
            player.sendMessage(Messages.comp(player, "cmd.goto.no-game"));
            return;
        }
        Location dest = winnerLocation != null ? winnerLocation : spawnLocation;
        if (dest == null) {
            player.sendMessage(Messages.comp(player, "cmd.goto.no-location"));
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        player.teleport(dest);
        player.sendMessage(Messages.comp(player, "cmd.goto.success"));
    }

    // ── Lobby task ────────────────────────────────────────────────────────────

    /**
     * Starts the recurring lobby task that updates scoreboards in the IDLE and ENDED states.
     * Already running tasks are cancelled first.
     */
    public void startLobbyTask() {
        if (!plugin.isEnabled()) return;
        setDayCycle(false); // Lobby/end = cycle frozen
        if (lobbyTask != null) { lobbyTask.cancel(); lobbyTask = null; }
        lobbyTask = new BukkitRunnable() {
            @Override public void run() {
                if (state == GameState.IDLE) {
                    sidebar.updateLobby(GameManager.this);
                } else if (state == GameState.ENDED) {
                    sidebar.updateEnded(finalResults, players, GameManager.this);
                } else {
                    // STARTING or RUNNING: no lobby task needed
                    cancel(); lobbyTask = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    /** Called when a player joins the server (next tick after join). */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();

        if (state == GameState.STARTING) {
            // During countdown: (re-)joining is allowed
            PlayerData pd = players.computeIfAbsent(uuid, k -> new PlayerData(player));
            pd.setDisconnected(false);
            if (spawnLocation != null) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(spawnLocation);
                player.setHealth(20.0);
                player.setFoodLevel(20);
                player.setSaturation(20f);
            }
            sidebar.setupStarting(players.values(), serverName);

        } else if (state == GameState.RUNNING) {
            PlayerData pd = players.get(uuid);

            if (pd != null && pd.isAlive() && pd.isDisconnected()) {
                // Player returns – resume racing
                pd.setDisconnected(false);
                player.setGameMode(GameMode.SURVIVAL);
                assignBorder(pd, player, startLocation.getX(), startLocation.getZ());
                sidebar.setupPlayerBoard(pd, player, serverName);
                broadcast("game.reconnect", player.getName());

            } else if (pd != null && !pd.isAlive()) {
                // Was already dead → restore spectator scoreboard
                player.setGameMode(GameMode.SPECTATOR);
                sidebar.setupPlayerBoard(pd, player, serverName);
            }
            // pd == null: new player → already blocked by the LoginEvent
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    /**
     * Handles a player leaving. Freezes their distance and marks them as
     * disconnected (during RUNNING) or removes them from the participant list (during STARTING).
     * @param player the player who left the server
     */
    public void handleQuit(Player player) {
        PlayerData pd = players.get(player.getUniqueId());
        if (pd == null) return;

        if (state == GameState.STARTING) {
            // Left during countdown → remove from the list
            players.remove(player.getUniqueId());
            sidebar.removePlayer(pd);
            return;
        }
        if (state != GameState.RUNNING) return;
        if (!pd.isAlive()) return; // dead spectator leaves the server → ignore

        // Freeze distance, mark player as "absent" (not as dead)
        double dist = startLocation == null ? 0 : direction.getForwardDistance(
            startLocation.getX(), startLocation.getZ(),
            player.getLocation().getX(), player.getLocation().getZ()
        );
        pd.setFinalDistance(Math.max(0, dist));
        pd.setDisconnected(true);
        clearBorder(pd);
        broadcast("game.disconnect", player.getName(), fmt(dist));
        checkEndCondition();
    }

    // ── Helper methods ─────────────────────────────────────────────────────────

    /**
     * Returns all participants sorted descending by their running distance.
     * Also updates the stored distance for still-living online players.
     * @return sorted player list
     */
    List<PlayerData> getSorted() {
        // Living players: use current position
        for (PlayerData pd : players.values()) {
            if (!pd.isAlive() || startLocation == null) continue;
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            pd.setFinalDistance(Math.max(0, direction.getForwardDistance(
                startLocation.getX(), startLocation.getZ(),
                p.getLocation().getX(), p.getLocation().getZ()
            )));
        }
        return players.values().stream()
            .sorted(Comparator.comparingDouble(PlayerData::getFinalDistance).reversed())
            .collect(Collectors.toList());
    }

    /** Cancels all running BukkitTasks (countdown, border, update, action bar, PvP). */
    private void cancelTasks() {
        if (countdownTask != null)  { countdownTask.cancel();  countdownTask = null; }
        if (borderTask != null)     { borderTask.cancel();     borderTask = null; }
        if (updateTask != null)     { updateTask.cancel();     updateTask = null; }
        if (actionBarTask != null)  { actionBarTask.cancel();  actionBarTask = null; }
        if (pvpDelayTask != null)   { pvpDelayTask.cancel();   pvpDelayTask = null; }
    }

    /**
     * Schedules the delayed PVP activation after race start.
     * If pvpDelaySeconds=0, PVP is activated immediately.
     * The last pvpCountdownSeconds are announced every second.
     */
    private void schedulePvpActivation() {
        if (pvpDelaySeconds <= 0) {
            pvpActive = true;
            broadcast("game.pvp.activated");
            titleAll("game.pvp.title", "game.pvp.subtitle");
            sound(Sound.ENTITY_WITHER_SPAWN, 1f);
            return;
        }

        // Announcement at start
        broadcast("game.pvp.announce", pvpDelaySeconds);

        final int[] remaining = {pvpDelaySeconds};
        pvpDelayTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.RUNNING) { cancel(); pvpDelayTask = null; return; }
                if (paused) return; // delay timer pauses with the game
                remaining[0]--;
                if (remaining[0] <= 0) {
                    pvpActive = true;
                    broadcast("game.pvp.activated");
                    titleAll("game.pvp.title", "game.pvp.subtitle");
                    sound(Sound.ENTITY_WITHER_SPAWN, 1f);
                    cancel();
                    pvpDelayTask = null;
                    return;
                }
                if (remaining[0] <= pvpCountdownSeconds) {
                    broadcast("game.pvp.countdown", remaining[0]);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Controls the day-night cycle of the spawn world.
     * true = running (only during an active race), false = frozen (lobby, countdown, pause, end).
     */
    private void setDayCycle(boolean running) {
        World world = spawnLocation != null ? spawnLocation.getWorld()
            : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        if (world != null) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, running);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, running);
        }
    }

    /** Sends a localized message to every online player in their own language. */
    private void broadcast(String key, Object... args) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Messages.comp(p, key, args));
        }
    }

    /** Shows a title to all participants (3 s display time). */
    private void titleAll(String titleKey, String subtitleKey, Object... args) {
        showTitles(titleKey, subtitleKey,
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500)),
            args);
    }

    /** Shows a title to all participants with longer display time (5 s, for game end). */
    private void titleAllLong(String titleKey, String subtitleKey, Object... args) {
        showTitles(titleKey, subtitleKey,
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofMillis(1000)),
            args);
    }

    /**
     * Shows a title to all current participants in their respective language.
     * @param titleKey    message key for the main title
     * @param subtitleKey message key for the subtitle
     * @param times       fade-in/out durations
     * @param args        placeholder values for title and subtitle
     */
    private void showTitles(String titleKey, String subtitleKey, Title.Times times, Object... args) {
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.showTitle(Title.title(
                Messages.comp(p, titleKey, args),
                Messages.comp(p, subtitleKey, args),
                times));
        }
    }

    /**
     * Sends a ready-made component to all online players (for non-localizable messages).
     * @param msg message to send
     */
    private void broadcastRaw(Component msg) {
        Bukkit.broadcast(msg);
    }

    /**
     * Plays a sound for all participants at their respective positions.
     * @param s     the sound to play
     * @param pitch pitch (1.0 = normal)
     */
    private void sound(Sound s, float pitch) {
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), s, 1f, pitch);
        }
    }

    /** Formats a number with no decimal places. */
    private String fmt(double d) {
        return String.format("%.0f", d);
    }

    /** Formats a {@link Location} as a human-readable world/X/Y/Z string. */
    private String fmtLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "/" + l.getBlockY() + "/" + l.getBlockZ();
    }

    /**
     * Creates a formatted status line (label gray, value white).
     * @param label label text
     * @param val   value to display
     * @return finished component
     */
    private Component line(String label, String val) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
            .append(Component.text(val, NamedTextColor.WHITE));
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    /** @return current game state */
    public GameState getState()  { return state; }
    /** @return {@code true} if the game is currently paused */
    public boolean isPaused()       { return paused; }
    /** @return {@code true} if PVP is enabled in the configuration */
    public boolean isPvpEnabled()   { return pvpEnabled; }
    /** PVP is currently active: only during RUNNING, after the delay has expired, and not while paused. */
    public boolean isPvpActive()    { return pvpActive && !paused; }
    /** @return {@code true} if a maximum time limit is configured */
    public boolean hasTimeLimit()   { return maxTimeSeconds > 0; }
    /** @return configured time limit in seconds (0 = unlimited) */
    public int getMaxTimeSeconds()  { return maxTimeSeconds; }

    /**
     * Returns the elapsed race time in seconds so far (pause times are subtracted).
     * @return elapsed seconds since race start
     */
    public int getElapsedSeconds() {
        if (raceStartTime == 0) return 0;
        long pausedExtra = paused ? (System.currentTimeMillis() - pauseStartMs) : 0;
        return (int) ((System.currentTimeMillis() - raceStartTime - totalPausedMs - pausedExtra) / 1000);
    }

    /**
     * Returns the remaining time until the time limit.
     * @return remaining seconds (0 if no time limit or expired)
     */
    public int getRemainingSeconds() {
        return Math.max(0, maxTimeSeconds - getElapsedSeconds());
    }

    /** @return formatted timer string for the sidebar (default language) */
    public String getTimerDisplay() {
        return getTimerDisplay(null);
    }

    /**
     * Returns the formatted timer string for the sidebar.
     * During pause a pause text is shown; with a time limit the remaining time (color-coded),
     * otherwise the elapsed time.
     * @param viewer player whose language is used (may be {@code null})
     * @return fully formatted timer string with color codes
     */
    public String getTimerDisplay(Player viewer) {
        if (paused) return viewer != null
            ? Messages.str(viewer, "sidebar.game.timer-pause")
            : Messages.str("sidebar.game.timer-pause");
        int secs = hasTimeLimit() ? getRemainingSeconds() : getElapsedSeconds();
        String color = !hasTimeLimit() ? "§f"
            : getRemainingSeconds() > maxTimeSeconds / 2 ? "§a"
            : getRemainingSeconds() > maxTimeSeconds / 4 ? "§e"
            : "§c";
        return color + String.format("%02d:%02d", secs / 60, secs % 60);
    }
    /** @return measurement point at the outer edge of the cage start wall, or {@code null} if not set */
    public Location getStartLocation()    { return startLocation; }
    /** @return spawn position in the center of the cage, or {@code null} if no cage has been built */
    public Location getSpawnLocation()    { return spawnLocation; }
    /** @return last known position of the winner after game end, or {@code null} */
    public Location getWinnerLocation()   { return winnerLocation; }
    /** @return {@code true} if non-admin players are allowed to join */
    public boolean isServerOpen()         { return serverOpen; }
    /** @return {@code true} if a cage has been built (spawn location present) */
    public boolean isCageBuilt()          { return spawnLocation != null; }
    /** @return displayed server name (for scoreboard title) */
    public String  getServerName()        { return serverName; }
    /** @return final result list sorted descending by distance (empty if no game has ended) */
    public List<PlayerData> getResults()  { return finalResults; }
    /** @return configured run direction */
    public RunDirection getDirection() { return direction; }
    /** @return half WorldBorder width in blocks */
    public int getCorridorWidth() { return corridorWidth; }
    /** @return all participants of the current game (UUID → PlayerData) */
    public Map<UUID, PlayerData> getPlayers() { return players; }
    /** @return all protected cage block positions */
    public Set<Location> getCageLocations() { return cageLocations; }
    /**
     * Checks whether a player is registered as a participant of the current game.
     * @param p the player to check
     * @return {@code true} if the player is in the participant list
     */
    public boolean isInGame(Player p) { return players.containsKey(p.getUniqueId()); }
}
