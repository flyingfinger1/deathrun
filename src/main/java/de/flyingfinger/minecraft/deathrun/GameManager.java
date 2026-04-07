package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class GameManager {

    private final DeathrunPlugin plugin;

    // ── Konfiguration ─────────────────────────────────────────────────────────
    private Location startLocation;  // Messpunkt: Außenkante der Käfigwand
    private Location spawnLocation;  // Spieler-Spawn: Mitte des Käfigs
    private RunDirection direction = RunDirection.NORTH;
    private int corridorWidth = 30;
    private int countdownSeconds = 10;
    private String serverName = "DeathRun";
    private int cageRadius = 3;
    private double  borderDamagePerBlock = 0.5;
    private double  borderDamageBuffer   = 0.0;
    private boolean pvpEnabled           = false;
    private int     maxTimeSeconds       = 0;

    // ── Spielzustand ──────────────────────────────────────────────────────────
    private GameState state  = GameState.IDLE;
    private boolean   paused = false;
    private long raceStartTime  = 0;   // ms, wenn Rennen beginnt
    private long totalPausedMs  = 0;   // akkumulierte Pause-Zeit
    private long pauseStartMs   = 0;   // ms, wenn aktuelle Pause startete
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private Set<Location> cageLocations = new HashSet<>();

    // ── Server-Zustand ────────────────────────────────────────────────────────
    private boolean serverOpen    = false;  // false = nur OPs dürfen joinen
    private Location winnerLocation = null; // Letzter Aufenthaltsort des Gewinners
    private List<PlayerData> finalResults = List.of(); // Ergebnis nach Spielende

    // ── Tasks ─────────────────────────────────────────────────────────────────
    private BukkitTask countdownTask;
    private BukkitTask borderTask;
    private BukkitTask updateTask;
    private BukkitTask actionBarTask;
    private BukkitTask lobbyTask;

    // ── Manager ───────────────────────────────────────────────────────────────
    private final CageBuilder cageBuilder = new CageBuilder();
    private final SidebarManager sidebar = new SidebarManager();
    private final TablistManager tablist = new TablistManager();

    public GameManager(DeathrunPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    // ── Konfiguration laden ───────────────────────────────────────────────────

    private void loadConfig() {
        var cfg = plugin.getConfig();
        corridorWidth = cfg.getInt("corridor-width", 30);
        countdownSeconds = cfg.getInt("countdown", 10);
        serverName = cfg.getString("server-name", "DeathRun");
        cageRadius = cfg.getInt("cage-radius", 3);
        try {
            direction = RunDirection.valueOf(cfg.getString("direction", "NORTH").toUpperCase());
        } catch (IllegalArgumentException ignored) {}
        borderDamagePerBlock = cfg.getDouble("border-damage-per-block", 0.5);
        borderDamageBuffer   = cfg.getDouble("border-damage-buffer", 0.0);
        pvpEnabled           = cfg.getBoolean("pvp", false);
        maxTimeSeconds       = cfg.getInt("max-time", 0);

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
    }

    // ── Admin-Befehle ─────────────────────────────────────────────────────────

    public void buildCage(Player sender) {
        // Käfig-Mittelpunkt = aktuelle Spielerposition (Block-Mitte)
        Location center = sender.getLocation().clone();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int r  = CageBuilder.getEffectiveRadius(cageRadius);

        // Käfig bauen
        cageLocations = cageBuilder.buildCage(center, cageRadius, direction);

        // Spawn = Mitte des Käfigs, Blickrichtung = Laufrichtung
        spawnLocation = new Location(center.getWorld(),
            cx + 0.5, cy, cz + 0.5, spawnYaw(direction), 0f);

        // Messpunkt = Außenfläche der Indicator-Wand
        startLocation = spawnLocation.clone();
        switch (direction) {
            case NORTH -> startLocation.setZ(cz - r);
            case SOUTH -> startLocation.setZ(cz + r + 1);
            case EAST  -> startLocation.setX(cx + r + 1);
            case WEST  -> startLocation.setX(cx - r);
        }

        // Config speichern
        saveLocCfg("start", startLocation);
        saveLocCfg("spawn", spawnLocation);
        plugin.saveConfig();

        // World-Spawn setzen
        center.getWorld().setSpawnLocation(spawnLocation);

        sender.sendMessage(Messages.comp(sender, "cmd.buildcage.success", direction.getDisplayName()));
    }

    private float spawnYaw(RunDirection dir) {
        return switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST  -> -90f;
            case WEST  -> 90f;
        };
    }

    private void saveLocCfg(String key, Location loc) {
        var cfg = plugin.getConfig();
        cfg.set(key + ".world", loc.getWorld().getName());
        cfg.set(key + ".x", loc.getX());
        cfg.set(key + ".y", loc.getY());
        cfg.set(key + ".z", loc.getZ());
        if (loc.getYaw() != 0f) cfg.set(key + ".yaw", loc.getYaw());
    }

    public void removeCage(Player sender) {
        cageBuilder.removeCage(cageLocations);
        cageLocations.clear();
        sender.sendMessage(Messages.comp(sender, "cmd.removecage.success"));
    }

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

    public void setDirection(CommandSender sender, String arg) {
        try {
            direction = RunDirection.valueOf(arg.toUpperCase());
            plugin.getConfig().set("direction", direction.name());
            plugin.saveConfig();
            sender.sendMessage(Messages.comp(sender, "cmd.setdirection.success", direction.getDisplayName()));

            // Wenn Käfig bereits steht: Tür auf neue Seite umbauen & Messpunkt aktualisieren
            if (!cageLocations.isEmpty() && spawnLocation != null) {
                int cx = spawnLocation.getBlockX();
                int cy = spawnLocation.getBlockY();
                int cz = spawnLocation.getBlockZ();
                int r  = CageBuilder.getEffectiveRadius(cageRadius);

                cageBuilder.changeDirection(cx, cy, cz, cageRadius, direction);

                // Messpunkt = Außenfläche der neuen Indicator-Wand
                startLocation = spawnLocation.clone();
                switch (direction) {
                    case NORTH -> startLocation.setZ(cz - r);
                    case SOUTH -> startLocation.setZ(cz + r + 1);
                    case EAST  -> startLocation.setX(cx + r + 1);
                    case WEST  -> startLocation.setX(cx - r);
                }
                saveLocCfg("start", startLocation);
                plugin.saveConfig();

                // Spawn-Blickrichtung anpassen
                spawnLocation.setYaw(spawnYaw(direction));
                saveLocCfg("spawn", spawnLocation);
                plugin.saveConfig();
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Messages.comp(sender, "cmd.setdirection.invalid"));
        }
    }

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

    public void showStatus(CommandSender sender) {
        sender.sendMessage(Messages.comp(sender, "cmd.status.header"));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.state"), state.name()));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.spawn"),
            spawnLocation == null ? Messages.str(sender, "cmd.status.not-set") : fmtLoc(spawnLocation)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.start"),
            startLocation == null ? Messages.str(sender, "cmd.status.not-set") : fmtLoc(startLocation)));
        sender.sendMessage(line(Messages.str(sender, "cmd.status.direction"), direction.getDisplayName()));
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

    // ── Spielstart ────────────────────────────────────────────────────────────

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
        // Lobby-Boards abbauen, Lobby-Task stoppen
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

        // Scoreboard schon während Countdown zeigen
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

    private void beginRace() {
        state = GameState.RUNNING;
        raceStartTime = System.currentTimeMillis();
        totalPausedMs = 0;
        setDayCycle(true); // Zyklus läuft nur während aktiven Rennens

        // Käfigtür ZUERST öffnen – unabhängig von allem anderen
        cageBuilder.openCage();
        cageLocations.removeAll(cageBuilder.getLastRemovedLocations());

        double sx = startLocation.getX();
        double sz = startLocation.getZ();

        // WorldBorder pro Spieler setzen
        for (PlayerData pd : players.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            assignBorder(pd, p, sx, sz);
        }

        // Sidebar initialisieren – in try-catch, damit ein Fehler hier nie openCage blockiert
        try {
            sidebar.setup(players.values(), serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("Sidebar-Fehler beim Rennstart: " + e.getMessage());
        }

        // Border alle 2 Ticks aktualisieren
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

        // Scoreboard + Tablist + Zeitlimit-Check jede Sekunde
        updateTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }
                // Zeitlimit prüfen (nicht während Pause)
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

        broadcast("game.countdown.go", direction.getDisplayName());
        sound(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
    }

    // ── WorldBorder ───────────────────────────────────────────────────────────

    private void assignBorder(PlayerData pd, Player p, double sx, double sz) {
        try {
            // Erfordert Paper-API (Player.setWorldBorder + Bukkit.createWorldBorder)
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

    private void clearBorder(PlayerData pd) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p != null) {
            try { p.setWorldBorder(null); } catch (Exception ignored) {}
        }
        pd.setPersonalBorder(null);
    }

    // ── Tod ───────────────────────────────────────────────────────────────────

    /** Wird aus GameListener aufgerufen, wenn ein Spieler im Rennen stirbt. */
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

    /** Nach dem Respawn: Spieler in Spektatormodus setzen. */
    public void handleRespawn(Player player) {
        PlayerData pd = players.get(player.getUniqueId());
        if (pd == null || pd.isAlive()) return;
        player.setGameMode(GameMode.SPECTATOR);
    }

    // ── Endkontrolle ──────────────────────────────────────────────────────────

    private void checkEndCondition() {
        if (state != GameState.RUNNING) return;

        // Alle lebenden Spieler (auch offline)
        List<PlayerData> aliveAll = players.values().stream()
            .filter(PlayerData::isAlive).collect(Collectors.toList());

        if (aliveAll.isEmpty()) { endGame(); return; }

        // Nur online-lebende Spieler
        List<PlayerData> aliveOnline = aliveAll.stream()
            .filter(pd -> Bukkit.getPlayer(pd.getUuid()) != null)
            .collect(Collectors.toList());

        // Alle lebenden Spieler sind gerade offline → abwarten ob sie zurückkommen
        if (aliveOnline.isEmpty()) return;

        boolean anyDead = players.values().stream().anyMatch(p -> !p.isAlive());

        // Letzter Überlebender (kein weiterer lebend, egal ob online/offline)
        if (aliveAll.size() == 1 && anyDead) {
            Player last = Bukkit.getPlayer(aliveAll.get(0).getUuid());
            if (last == null) return; // disconnected – warten

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

    // ── Spielende ─────────────────────────────────────────────────────────────

    private void endGame() {
        if (state == GameState.ENDED) return;
        state = GameState.ENDED;

        cancelTasks();
        tablist.reset(players.values());

        finalResults = getSorted();

        // Gewinner-Position merken (bevor Gamemode geändert wird)
        winnerLocation = null;
        if (!finalResults.isEmpty()) {
            Player winner = Bukkit.getPlayer(finalResults.get(0).getUuid());
            if (winner != null) winnerLocation = winner.getLocation().clone();
        }

        // Alle Spieler zurück in Survival
        for (PlayerData pd : players.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.setGameMode(GameMode.SURVIVAL);
        }

        // Ergebnis im Chat – jeder Spieler bekommt seine Sprache
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

        // Klickbare Teleport-Nachricht (wenn Gewinner-Position bekannt)
        if (winnerLocation != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Component gotoMsg = Messages.comp(p, "game.end.goto-prefix")
                    .append(Messages.comp(p, "game.end.goto-link")
                        .clickEvent(ClickEvent.runCommand("/dr goto"))
                        .hoverEvent(HoverEvent.showText(Messages.comp(p, "game.end.goto-hover"))));
                p.sendMessage(gotoMsg);
            }
        }

        // Tag-Nacht-Zyklus anhalten & End-Scoreboard aufbauen
        setDayCycle(false);
        sidebar.setupEnded(finalResults, players, serverName);
        startLobbyTask();
    }

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
            // Action-Bar alle 0.5s anzeigen solange pausiert
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
            // Action-Bar löschen
            for (UUID uuid : players.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendActionBar(Component.empty());
            }
            broadcast("game.pause.resumed");
        }
    }

    public void stopGame(CommandSender sender) {
        if (state == GameState.IDLE) {
            sender.sendMessage(Messages.comp(sender, "cmd.stop.no-game"));
            return;
        }
        forceStop();
        sender.sendMessage(Messages.comp(sender, "cmd.stop.success"));
    }

    public void forceStop() {
        // Tag-Nacht-Zyklus anhalten (IDLE/ENDED = immer eingefroren)
        setDayCycle(false);
        paused = false;
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
        // Käfigtür wieder schließen
        cageBuilder.closeCage();
        cageLocations.addAll(cageBuilder.getLastRemovedLocations());
        startLobbyTask();
    }

    // ── Server öffnen / schließen ─────────────────────────────────────────────

    public void openServer(CommandSender sender) {
        if (spawnLocation == null) {
            sender.sendMessage(Messages.comp(sender, "cmd.open.no-cage"));
            return;
        }
        serverOpen = true;
        sender.sendMessage(Messages.comp(sender, "cmd.open.success"));
        broadcast("cmd.open.broadcast");
    }

    public void closeServer(CommandSender sender) {
        serverOpen = false;
        sender.sendMessage(Messages.comp(sender, "cmd.close.success"));
    }

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

    // ── Lobby-Task ────────────────────────────────────────────────────────────

    public void startLobbyTask() {
        if (!plugin.isEnabled()) return;
        setDayCycle(false); // Lobby/Ende = Zyklus eingefroren
        if (lobbyTask != null) { lobbyTask.cancel(); lobbyTask = null; }
        lobbyTask = new BukkitRunnable() {
            @Override public void run() {
                if (state == GameState.IDLE) {
                    sidebar.updateLobby(GameManager.this);
                } else if (state == GameState.ENDED) {
                    sidebar.updateEnded(finalResults, players, GameManager.this);
                } else {
                    // STARTING oder RUNNING: kein Lobby-Task nötig
                    cancel(); lobbyTask = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    /** Wird aufgerufen, wenn ein Spieler dem Server beitritt (neuer Tick nach Join). */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();

        if (state == GameState.STARTING) {
            // Beim Countdown: (wieder-)beitreten ist erlaubt
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
                // Spieler kommt zurück – Rennen fortsetzen
                pd.setDisconnected(false);
                player.setGameMode(GameMode.SURVIVAL);
                assignBorder(pd, player, startLocation.getX(), startLocation.getZ());
                sidebar.setupPlayerBoard(pd, player, serverName);
                broadcast("game.reconnect", player.getName());

            } else if (pd != null && !pd.isAlive()) {
                // War schon tot → Zuschauer-Scoreboard wiederherstellen
                player.setGameMode(GameMode.SPECTATOR);
                sidebar.setupPlayerBoard(pd, player, serverName);
            }
            // pd == null: neuer Spieler → vom LoginEvent bereits geblockt
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    public void handleQuit(Player player) {
        PlayerData pd = players.get(player.getUniqueId());
        if (pd == null) return;

        if (state == GameState.STARTING) {
            // Während Countdown raus → aus der Liste entfernen
            players.remove(player.getUniqueId());
            sidebar.removePlayer(pd);
            return;
        }
        if (state != GameState.RUNNING) return;
        if (!pd.isAlive()) return; // toter Zuschauer verlässt den Server → ignorieren

        // Distanz einfrieren, Spieler als "abwesend" markieren (nicht als tot)
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

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    List<PlayerData> getSorted() {
        // Lebende Spieler: aktuelle Position verwenden
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

    private void cancelTasks() {
        if (countdownTask != null)  { countdownTask.cancel();  countdownTask = null; }
        if (borderTask != null)     { borderTask.cancel();     borderTask = null; }
        if (updateTask != null)     { updateTask.cancel();     updateTask = null; }
        if (actionBarTask != null)  { actionBarTask.cancel();  actionBarTask = null; }
    }

    /**
     * Steuert den Tag-Nacht-Zyklus der Spawn-Welt.
     * true = läuft (nur während aktiven Rennens), false = eingefroren (Lobby, Countdown, Pause, Ende).
     */
    private void setDayCycle(boolean running) {
        World world = spawnLocation != null ? spawnLocation.getWorld()
            : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        if (world != null) world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, running);
    }

    /** Sendet eine lokalisierte Nachricht an jeden online Spieler in seiner eigenen Sprache. */
    private void broadcast(String key, Object... args) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Messages.comp(p, key, args));
        }
    }

    /** Sendet eine fertige Component an alle (für nicht-lokalisierbare Nachrichten). */
    private void broadcastRaw(Component msg) {
        Bukkit.broadcast(msg);
    }

    private void sound(Sound s, float pitch) {
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), s, 1f, pitch);
        }
    }

    private String fmt(double d) {
        return String.format("%.0f", d);
    }

    private String fmtLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "/" + l.getBlockY() + "/" + l.getBlockZ();
    }

    private Component line(String label, String val) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
            .append(Component.text(val, NamedTextColor.WHITE));
    }

    // ── Getter ────────────────────────────────────────────────────────────────

    public GameState getState()  { return state; }
    public boolean isPaused()       { return paused; }
    public boolean isPvpEnabled()   { return pvpEnabled; }
    public boolean hasTimeLimit()   { return maxTimeSeconds > 0; }
    public int getMaxTimeSeconds()  { return maxTimeSeconds; }

    public int getElapsedSeconds() {
        if (raceStartTime == 0) return 0;
        long pausedExtra = paused ? (System.currentTimeMillis() - pauseStartMs) : 0;
        return (int) ((System.currentTimeMillis() - raceStartTime - totalPausedMs - pausedExtra) / 1000);
    }

    public int getRemainingSeconds() {
        return Math.max(0, maxTimeSeconds - getElapsedSeconds());
    }

    public String getTimerDisplay() {
        return getTimerDisplay(null);
    }

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
    public Location getStartLocation()    { return startLocation; }
    public Location getSpawnLocation()    { return spawnLocation; }
    public Location getWinnerLocation()   { return winnerLocation; }
    public boolean isServerOpen()         { return serverOpen; }
    public boolean isCageBuilt()          { return spawnLocation != null; }
    public String  getServerName()        { return serverName; }
    public List<PlayerData> getResults()  { return finalResults; }
    public RunDirection getDirection() { return direction; }
    public int getCorridorWidth() { return corridorWidth; }
    public Map<UUID, PlayerData> getPlayers() { return players; }
    public Set<Location> getCageLocations() { return cageLocations; }
    public boolean isInGame(Player p) { return players.containsKey(p.getUniqueId()); }
}
