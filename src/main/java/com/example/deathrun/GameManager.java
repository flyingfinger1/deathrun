package com.example.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

        sender.sendMessage(Component.text(
            "✔ Käfig gebaut | Spawn & Messpunkt gesetzt | Richtung: " + direction.getDisplayName(),
            NamedTextColor.GREEN));
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
        sender.sendMessage(Component.text("✔ Käfig entfernt.", NamedTextColor.GREEN));
    }

    public void setCorridorWidth(CommandSender sender, int width) {
        if (width < 5 || width > 500) {
            send(sender, "Breite muss zwischen 5 und 500 liegen.", NamedTextColor.RED);
            return;
        }
        corridorWidth = width;
        plugin.getConfig().set("corridor-width", width);
        plugin.saveConfig();
        sender.sendMessage(Component.text("✔ Korridorbreite: ±" + width + " Blöcke.", NamedTextColor.GREEN));
    }

    public void setDirection(CommandSender sender, String arg) {
        try {
            direction = RunDirection.valueOf(arg.toUpperCase());
            plugin.getConfig().set("direction", direction.name());
            plugin.saveConfig();
            sender.sendMessage(Component.text("✔ Laufrichtung: " + direction.getDisplayName(), NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            send(sender, "Unbekannte Richtung. Nutze NORTH, SOUTH, EAST oder WEST.", NamedTextColor.RED);
        }
    }

    public void setMaxTime(CommandSender sender, int minutes) {
        if (minutes < 0) { send(sender, "Zeit muss >= 0 sein (0 = kein Limit).", NamedTextColor.RED); return; }
        maxTimeSeconds = minutes * 60;
        plugin.getConfig().set("max-time", maxTimeSeconds);
        plugin.saveConfig();
        sender.sendMessage(Component.text("✔ Maximale Spielzeit: " +
            (maxTimeSeconds == 0 ? "kein Limit" : minutes + " Min."), NamedTextColor.GREEN));
    }

    public void showStatus(CommandSender sender) {
        sender.sendMessage(Component.text("── Deathrun Status ──", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sender.sendMessage(line("Zustand", state.name()));
        sender.sendMessage(line("Spawn (Käfig-Mitte)", spawnLocation == null ? "nicht gesetzt" : fmtLoc(spawnLocation)));
        sender.sendMessage(line("Messpunkt",           startLocation == null ? "nicht gesetzt" : fmtLoc(startLocation)));
        sender.sendMessage(line("Richtung", direction.getDisplayName()));
        sender.sendMessage(line("Korridor", "±" + corridorWidth + " Blöcke"));
        sender.sendMessage(line("Countdown", countdownSeconds + "s"));
        sender.sendMessage(line("Max-Zeit", maxTimeSeconds == 0 ? "kein Limit" : (maxTimeSeconds / 60) + " Min."));
        sender.sendMessage(line("Spieler online", String.valueOf(Bukkit.getOnlinePlayers().size())));
    }

    // ── Spielstart ────────────────────────────────────────────────────────────

    public boolean startGame(CommandSender sender) {
        if (state == GameState.STARTING || state == GameState.RUNNING) {
            send(sender, "Spiel läuft bereits!", NamedTextColor.RED);
            return false;
        }
        if (spawnLocation == null) {
            send(sender, "Kein Käfig gesetzt! Nutze /dr buildcage", NamedTextColor.RED);
            return false;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            send(sender, "Keine Spieler online!", NamedTextColor.RED);
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

        broadcast(Component.text("Das Deathrun startet in " + countdownSeconds + " Sekunden!",
            NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

        // Scoreboard schon während Countdown zeigen
        sidebar.setupStarting(players.values(), serverName);
        sidebar.updateStarting(players.values(), countdownSeconds);

        final int[] rem = {countdownSeconds};
        countdownTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != GameState.STARTING) { cancel(); return; }
                if (rem[0] <= 0) { cancel(); beginRace(); return; }
                if (rem[0] <= 5 || rem[0] % 10 == 0) {
                    broadcast(Component.text("Start in " + rem[0] + "…", NamedTextColor.YELLOW));
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
                    broadcast(Component.text("⏱ Zeit abgelaufen! Das Rennen ist vorbei.",
                        NamedTextColor.RED).decorate(TextDecoration.BOLD));
                    endGame();
                    return;
                }
                List<PlayerData> sorted = getSorted();
                sidebar.update(sorted, players, GameManager.this);
                tablist.update(sorted, GameManager.this);
                checkEndCondition();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        broadcast(Component.text("LOS! Rennt Richtung " + direction.getDisplayName() + "!",
            NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
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

        // Spektatormodus nach dem nächsten Tick (nach Respawn)
        broadcast(Component.text("☠ ", NamedTextColor.RED)
            .append(Component.text(player.getName(), NamedTextColor.WHITE))
            .append(Component.text(" ist gestorben! Distanz: ", NamedTextColor.RED))
            .append(Component.text(fmt(dist) + "m", NamedTextColor.GOLD)));

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

        // Ergebnis im Chat
        broadcast(Component.text("══════ ERGEBNIS ══════", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        for (int i = 0; i < finalResults.size(); i++) {
            PlayerData pd = finalResults.get(i);
            NamedTextColor c = i == 0 ? NamedTextColor.GOLD
                             : i == 1 ? NamedTextColor.YELLOW
                             : i == 2 ? NamedTextColor.GREEN
                             : NamedTextColor.GRAY;
            broadcast(Component.text("#" + (i + 1) + " " + pd.getName() + " – " + fmt(pd.getFinalDistance()) + "m", c));
        }

        // Klickbare Teleport-Nachricht (wenn Gewinner-Position bekannt)
        if (winnerLocation != null) {
            Component gotoMsg = Component.text("» ", NamedTextColor.GOLD)
                .append(Component.text("[Zum Gewinner-Ort teleportieren]", NamedTextColor.YELLOW)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/dr goto"))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Klick zum Teleportieren!", NamedTextColor.GREEN))));
            broadcast(gotoMsg);
        }

        // End-Scoreboard aufbauen und Lobby-Task starten
        sidebar.setupEnded(finalResults, players, serverName);
        startLobbyTask();
    }

    public void togglePause(CommandSender sender) {
        if (state != GameState.RUNNING) {
            send(sender, "Pause nur während eines laufenden Spiels möglich.", NamedTextColor.RED);
            return;
        }
        paused = !paused;
        if (paused) {
            pauseStartMs = System.currentTimeMillis();
            if (spawnLocation != null) spawnLocation.getWorld().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            broadcast(Component.text("⏸ Spiel pausiert.", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
            // Action-Bar alle 0.5s anzeigen solange pausiert
            actionBarTask = new BukkitRunnable() {
                @Override public void run() {
                    if (!paused) { cancel(); actionBarTask = null; return; }
                    Component msg = Component.text("⏸  PAUSE  ⏸", NamedTextColor.YELLOW)
                                             .decorate(TextDecoration.BOLD);
                    for (UUID uuid : players.keySet()) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) p.sendActionBar(msg);
                    }
                }
            }.runTaskTimer(plugin, 0L, 10L);
        } else {
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            if (spawnLocation != null) spawnLocation.getWorld().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            if (actionBarTask != null) { actionBarTask.cancel(); actionBarTask = null; }
            // Action-Bar löschen
            for (UUID uuid : players.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendActionBar(Component.empty());
            }
            broadcast(Component.text("▶ Spiel fortgesetzt.", NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
        }
    }

    public void stopGame(CommandSender sender) {
        if (state == GameState.IDLE) {
            send(sender, "Kein aktives Spiel!", NamedTextColor.RED);
            return;
        }
        forceStop();
        sender.sendMessage(Component.text("✔ Spiel zurückgesetzt.", NamedTextColor.GREEN));
    }

    public void forceStop() {
        if (paused && spawnLocation != null) {
            spawnLocation.getWorld().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        }
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
        startLobbyTask();
    }

    // ── Server öffnen / schließen ─────────────────────────────────────────────

    public void openServer(CommandSender sender) {
        if (spawnLocation == null) {
            send(sender, "Käfig muss zuerst gebaut werden! /dr buildcage", NamedTextColor.RED);
            return;
        }
        serverOpen = true;
        sender.sendMessage(Component.text("✔ Server ist jetzt für alle geöffnet!", NamedTextColor.GREEN));
        broadcast(Component.text("🟢 Der Server ist jetzt für alle Spieler freigegeben!",
            NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
    }

    public void closeServer(CommandSender sender) {
        serverOpen = false;
        sender.sendMessage(Component.text("✔ Server ist jetzt gesperrt (nur OPs).", NamedTextColor.YELLOW));
    }

    public void handleGoto(Player player) {
        if (finalResults.isEmpty()) {
            send(player, "Kein abgeschlossenes Spiel verfügbar.", NamedTextColor.RED);
            return;
        }
        Location dest = winnerLocation != null ? winnerLocation : spawnLocation;
        if (dest == null) { send(player, "Zielposition nicht verfügbar.", NamedTextColor.RED); return; }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        player.teleport(dest);
        send(player, "Teleportiert zum Gewinner-Ort!", NamedTextColor.GREEN);
    }

    // ── Lobby-Task ────────────────────────────────────────────────────────────

    public void startLobbyTask() {
        if (!plugin.isEnabled()) return;
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
                broadcast(Component.text("▶ " + player.getName() + " ist wieder dabei!",
                    NamedTextColor.GREEN));

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
        broadcast(Component.text("⚡ " + player.getName() +
            " hat die Verbindung getrennt. (" + fmt(dist) + "m) [kann zurückkehren]",
            NamedTextColor.GRAY));
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

    private void broadcast(Component msg) {
        Bukkit.broadcast(msg);
    }

    private void sound(Sound s, float pitch) {
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), s, 1f, pitch);
        }
    }

    private void send(CommandSender s, String msg, NamedTextColor color) {
        s.sendMessage(Component.text(msg, color));
    }

    private String fmt(double d) {
        return String.format("%.0f", d);
    }

    private String fmtLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "/" + l.getBlockY() + "/" + l.getBlockZ();
    }

    private Component line(String key, String val) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
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
        if (paused) return "§e⏸ PAUSE";
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
