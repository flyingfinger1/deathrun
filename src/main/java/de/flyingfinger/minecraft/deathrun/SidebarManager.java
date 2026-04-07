package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Pro Spieler ein eigenes Scoreboard:
 *  - SIDEBAR:      Servername, Top-5, eigener Rang/Distanz/EW
 *  - PLAYER_LIST:  Scores für Tab-Sortierung (alle Spieler auf jedem Board)
 */
public class SidebarManager {

    private static final String[] SLOTS = Arrays.stream(ChatColor.values())
            .map(ChatColor::toString)
            .toArray(String[]::new);

    private final Map<UUID, Scoreboard> boards       = new HashMap<>();
    private final Map<UUID, Objective>  sidebarObjs  = new HashMap<>();
    private final Map<UUID, Objective>  tabObjs       = new HashMap<>();

    // Lobby-Boards (IDLE / ENDED) – getrennt von den Spiel-Boards
    private final Map<UUID, Scoreboard> lobbyBoards = new HashMap<>();
    private final Map<UUID, Objective>  lobbyObjs   = new HashMap<>();

    /** Erstellt oder recycelt pro Spieler ein Scoreboard (für STARTING + RUNNING). */
    private Scoreboard getOrCreateBoard(PlayerData pd, Player p) {
        return boards.computeIfAbsent(pd.getUuid(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
            return sb;
        });
    }

    /** Initialisiert die Scoreboards für die Startphase (Countdown). */
    public void setupStarting(Collection<PlayerData> players, String serverName) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            Scoreboard sb = getOrCreateBoard(pd, p);
            p.setScoreboard(sb);

            Objective existing = sb.getObjective("dr_side");
            if (existing != null) existing.unregister();

            Objective obj = sb.registerNewObjective("dr_side", Criteria.DUMMY,
                Component.text(serverName, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            sidebarObjs.put(pd.getUuid(), obj);
        }
    }

    /** Aktualisiert den Countdown-Scoreboard (STARTING-Phase). */
    public void updateStarting(Collection<PlayerData> players, int remainingSeconds) {
        for (PlayerData pd : players) {
            Player viewer = Bukkit.getPlayer(pd.getUuid());
            Scoreboard sb  = boards.get(pd.getUuid());
            Objective  obj = sidebarObjs.get(pd.getUuid());
            if (sb == null || obj == null) continue;

            for (Team t : new ArrayList<>(sb.getTeams())) if (t.getName().startsWith("l")) t.unregister();
            for (String e : sb.getEntries()) sb.resetScores(e);

            int line = 14;
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.starting.countdown", remainingSeconds));
            line = setLine(sb, obj, line, " ");

            int shown = 0;
            for (PlayerData p2 : players) {
                if (shown >= 8) { setLine(sb, obj, line--, Messages.str(viewer, "sidebar.starting.more")); break; }
                line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.starting.player", p2.getName()));
                shown++;
            }

            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));
            setLine(sb, obj, line, Messages.str(viewer, "sidebar.starting.ready", players.size()));
        }
    }

    /** Initialisiert die Spieler-Scoreboards für alle Teilnehmer zu Beginn des Rennens. */
    public void setup(Collection<PlayerData> players, String serverName) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            setupPlayerBoard(pd, p, serverName);
        }
    }

    /** Richtet das Scoreboard für einen einzelnen Spieler ein (auch für Late-Joiner). */
    public void setupPlayerBoard(PlayerData pd, Player p, String serverName) {
        Scoreboard sb = getOrCreateBoard(pd, p);
        p.setScoreboard(sb);

        // Alle l-Teams aus der STARTING-Phase entfernen
        for (Team t : new ArrayList<>(sb.getTeams())) t.unregister();
        for (String e : sb.getEntries()) sb.resetScores(e);

        // Alte Objectives entfernen und neu registrieren
        Objective oldSide = sb.getObjective("dr_side");
        if (oldSide != null) oldSide.unregister();
        Objective oldTab = sb.getObjective("dr_tab");
        if (oldTab != null) oldTab.unregister();

        Objective sidebar = sb.registerNewObjective("dr_side", Criteria.DUMMY,
            Component.text(serverName, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);

        Objective tab = sb.registerNewObjective("dr_tab", Criteria.DUMMY, Component.empty());
        tab.setDisplaySlot(DisplaySlot.PLAYER_LIST);

        sidebarObjs.put(pd.getUuid(), sidebar);
        tabObjs.put(pd.getUuid(), tab);
    }

    /**
     * Aktualisiert Sidebar und Tab-Scores für alle Spieler während des laufenden Rennens.
     * @param sorted     Spielerliste, absteigend nach Distanz sortiert
     * @param allPlayers alle Teilnehmer (UUID → PlayerData)
     * @param gm         Referenz auf den GameManager für Live-Positionen und Timer
     */
    public void update(List<PlayerData> sorted, Map<UUID, PlayerData> allPlayers, GameManager gm) {
        for (PlayerData viewerData : allPlayers.values()) {
            Player viewer = Bukkit.getPlayer(viewerData.getUuid());
            if (viewer == null) continue;

            Scoreboard sb  = boards.get(viewerData.getUuid());
            Objective  obj = sidebarObjs.get(viewerData.getUuid());
            Objective  tab = tabObjs.get(viewerData.getUuid());
            if (sb == null || obj == null) continue;

            // ── Sidebar neu aufbauen ──────────────────────────────────────────

            // Nur Sidebar-Teams entfernen (Präfix "l"), Tab-Scores bleiben
            for (Team t : new ArrayList<>(sb.getTeams())) {
                if (t.getName().startsWith("l")) t.unregister();
            }
            for (String entry : sb.getEntries()) sb.resetScores(entry);

            int line = 14;
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));

            int topCount = Math.min(5, sorted.size());
            for (int i = 0; i < topCount; i++) {
                PlayerData pd   = sorted.get(i);
                double     dist = pd.isAlive() ? liveDistance(pd, gm) : pd.getFinalDistance();
                String     icon = Messages.str(viewer, pd.isAlive() ? "sidebar.game.icon-alive" : "sidebar.game.icon-dead");
                String     col  = i == 0 ? "§e" : i == 1 ? "§f" : "§7";
                line = setLine(sb, obj, line,
                    Messages.str(viewer, "sidebar.game.top-entry", col, i + 1, truncate(pd.getName(), 10), fmt(dist), icon));
            }

            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));

            int    myRank = getRank(sorted, viewerData.getUuid());
            double myDist = viewerData.isAlive() ? liveDistance(viewerData, gm) : viewerData.getFinalDistance();
            double ewDev  = gm.getStartLocation() == null ? 0 : gm.getDirection().getLateralDeviation(
                gm.getStartLocation().getX(), gm.getStartLocation().getZ(),
                viewer.getLocation().getX(),  viewer.getLocation().getZ());

            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.game.rank", myRank));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.game.dist", fmt(myDist)));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.game.ew", fmtDev(ewDev)));
            setLine(sb, obj, line,        Messages.str(viewer, "sidebar.game.time", gm.getTimerDisplay(viewer)));

            // ── Tab-Scores: alle Spieler auf diesem Board aktualisieren ───────
            if (tab != null) {
                for (int i = 0; i < sorted.size(); i++) {
                    PlayerData pd = sorted.get(i);
                    // Score = Distanz; höher = weiter oben in der Tabliste
                    tab.getScore(pd.getName()).setScore((int) Math.max(0, pd.getFinalDistance()));
                }
            }
        }
    }

    // ── Lobby-Board (IDLE) ────────────────────────────────────────────────────

    /**
     * Aktualisiert die Lobby-Scoreboards für alle aktuell online befindlichen Spieler.
     * Zeigt Setup-Hinweise (Käfig bauen, Server öffnen) oder den Wartestatus.
     * @param gm Referenz auf den GameManager für Käfig- und Serverstatus
     */
    public void updateLobby(GameManager gm) {
        boolean cageBuilt  = gm.isCageBuilt();
        boolean serverOpen = gm.isServerOpen();
        int     online     = Bukkit.getOnlinePlayers().size();

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            Scoreboard sb = lobbyBoards.computeIfAbsent(uuid, k ->
                Bukkit.getScoreboardManager().getNewScoreboard());
            p.setScoreboard(sb);

            Objective obj = sb.getObjective("dr_lobby");
            if (obj == null) {
                obj = sb.registerNewObjective("dr_lobby", Criteria.DUMMY,
                    Component.text(gm.getServerName(), NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);
                lobbyObjs.put(uuid, obj);
            }

            for (Team t : new ArrayList<>(sb.getTeams())) t.unregister();
            for (String e : sb.getEntries()) sb.resetScores(e);

            int line = 14;
            line = setLine(sb, obj, line, Messages.str(p, "sidebar.separator"));

            if (!serverOpen) {
                line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.locked"));
                if (!cageBuilt) {
                    line = setLine(sb, obj, line, " ");
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.step-label"));
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.step-cmd"));
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.step-hint"));
                } else {
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.cage-ok"));
                    line = setLine(sb, obj, line, " ");
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.open-cmd"));
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.open-hint"));
                    line = setLine(sb, obj, line, " ");
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.remove-cmd"));
                    line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.remove-hint"));
                }
            } else {
                line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.server-open"));
                line = setLine(sb, obj, line, " ");
                line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.waiting"));
                line = setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.start-cmd"));
            }

            line = setLine(sb, obj, line, Messages.str(p, "sidebar.separator"));
            setLine(sb, obj, line, Messages.str(p, "sidebar.lobby.online", online));
        }
    }

    // ── End-Board (ENDED) ─────────────────────────────────────────────────────

    /** Einmalig aufgerufen wenn das Spiel endet – baut die End-Boards auf. */
    public void setupEnded(List<PlayerData> sorted, Map<UUID, PlayerData> allPlayers, String serverName) {
        // Game-Boards in End-Boards umwandeln
        for (PlayerData pd : allPlayers.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            setupPlayerBoard(pd, p, serverName);
        }
    }

    /** Wird vom Lobby-Task aufgerufen um End-Boards aktuell zu halten. */
    public void updateEnded(List<PlayerData> sorted, Map<UUID, PlayerData> allPlayers, GameManager gm) {
        String winnerName = sorted.isEmpty() ? "?" : sorted.get(0).getName();

        for (PlayerData viewerData : allPlayers.values()) {
            Player viewer = Bukkit.getPlayer(viewerData.getUuid());
            if (viewer == null) continue;

            Scoreboard sb  = boards.get(viewerData.getUuid());
            Objective  obj = sidebarObjs.get(viewerData.getUuid());
            if (sb == null || obj == null) {
                setupPlayerBoard(viewerData, viewer, gm.getServerName());
                sb  = boards.get(viewerData.getUuid());
                obj = sidebarObjs.get(viewerData.getUuid());
                if (sb == null || obj == null) continue;
            }

            for (Team t : new ArrayList<>(sb.getTeams())) t.unregister();
            for (String e : sb.getEntries()) sb.resetScores(e);

            int line = 14;
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.end.winner-label"));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.end.winner-name", winnerName));
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));

            int topCount = Math.min(5, sorted.size());
            for (int i = 0; i < topCount; i++) {
                PlayerData pd  = sorted.get(i);
                String     col = i == 0 ? "§e" : i == 1 ? "§f" : "§7";
                line = setLine(sb, obj, line,
                    Messages.str(viewer, "sidebar.end.top-entry", col, i + 1,
                        truncate(pd.getName(), 10), fmt(pd.getFinalDistance())));
            }

            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.separator"));
            int    myRank = getRank(sorted, viewerData.getUuid());
            double myDist = viewerData.getFinalDistance();
            line = setLine(sb, obj, line, Messages.str(viewer, "sidebar.end.rank", myRank));
            setLine(sb, obj, line, Messages.str(viewer, "sidebar.end.dist", fmt(myDist)));
        }
    }

    /** Setzt alle Spieler auf das Haupt-Scoreboard zurück und verwirft die Lobby-Boards. */
    public void clearLobbyBoards() {
        for (Map.Entry<UUID, Scoreboard> e : lobbyBoards.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        lobbyBoards.clear();
        lobbyObjs.clear();
    }

    /** Entfernt einen einzelnen Spieler aus dem Scoreboard-System (z.B. Disconnect im Countdown). */
    public void removePlayer(PlayerData pd) {
        boards.remove(pd.getUuid());
        sidebarObjs.remove(pd.getUuid());
        tabObjs.remove(pd.getUuid());
    }

    /**
     * Setzt alle angegebenen Spieler auf das Haupt-Scoreboard zurück
     * und leert alle internen Scoreboard-Maps.
     * @param players die zu entfernenden Spieler
     */
    public void removeAll(Collection<PlayerData> players) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
        sidebarObjs.clear();
        tabObjs.clear();
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    /**
     * Schreibt eine Zeile in die Sidebar mithilfe eines Team-Präfixes.
     * @param sb      das Scoreboard des Betrachters
     * @param obj     das Sidebar-Objective
     * @param score   Zeilennummer (von oben: höher = weiter oben)
     * @param content anzuzeigender Text
     * @return {@code score - 1} für die nächste Zeile
     */
    private int setLine(Scoreboard sb, Objective obj, int score, String content) {
        String entry = SLOTS[score];
        Team   team  = sb.registerNewTeam("l" + score);
        team.addEntry(entry);
        team.prefix(Component.text(content));
        obj.getScore(entry).setScore(score);
        return score - 1;
    }

    /**
     * Gibt den 1-basierten Rang eines Spielers in der sortierten Liste zurück.
     * @param sorted nach Distanz sortierte Spielerliste
     * @param uuid   UUID des gesuchten Spielers
     * @return Rang (1 = Führender), oder {@code sorted.size()} falls nicht gefunden
     */
    private int getRank(List<PlayerData> sorted, UUID uuid) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getUuid().equals(uuid)) return i + 1;
        }
        return sorted.size();
    }

    /**
     * Berechnet die aktuelle Vorwärtsdistanz eines lebenden Spielers zur Startlinie.
     * Fällt auf {@link PlayerData#getFinalDistance()} zurück, falls der Spieler offline ist.
     */
    private double liveDistance(PlayerData pd, GameManager gm) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p == null || gm.getStartLocation() == null) return pd.getFinalDistance();
        return gm.getDirection().getForwardDistance(
            gm.getStartLocation().getX(), gm.getStartLocation().getZ(),
            p.getLocation().getX(),       p.getLocation().getZ());
    }

    /** Formatiert eine Distanz ohne Nachkommastellen (Minimum 0). */
    private String fmt(double d)     { return String.format("%.0f", Math.max(0, d)); }
    /** Formatiert eine seitliche Abweichung mit Vorzeichen. */
    private String fmtDev(double d)  { return (d >= 0 ? "+" : "") + String.format("%.0f", d); }
    /** Kürzt einen String auf {@code max} Zeichen und hängt „…" an falls nötig. */
    private String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
}
