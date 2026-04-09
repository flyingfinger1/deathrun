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
 * One individual scoreboard per player:
 *  - SIDEBAR:      server name, top 5, own rank/distance/lateral deviation
 *  - PLAYER_LIST:  scores for tab sorting (all players on every board)
 */
public class SidebarManager {

    /** Creates a new SidebarManager instance. */
    public SidebarManager() {}

    private static final String[] SLOTS = Arrays.stream(ChatColor.values())
            .map(ChatColor::toString)
            .toArray(String[]::new);

    private final Map<UUID, Scoreboard> boards       = new HashMap<>();
    private final Map<UUID, Objective>  sidebarObjs  = new HashMap<>();
    private final Map<UUID, Objective>  tabObjs       = new HashMap<>();

    // Lobby boards (IDLE / ENDED) – separate from the game boards
    private final Map<UUID, Scoreboard> lobbyBoards = new HashMap<>();
    private final Map<UUID, Objective>  lobbyObjs   = new HashMap<>();

    /** Creates or recycles a scoreboard per player (for STARTING + RUNNING). */
    private Scoreboard getOrCreateBoard(PlayerData pd, Player p) {
        return boards.computeIfAbsent(pd.getUuid(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
            return sb;
        });
    }

    /**
     * Initializes the scoreboards for the starting phase (countdown).
     *
     * @param players    all participants
     * @param serverName the server name displayed as the scoreboard title
     */
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

    /**
     * Updates the countdown scoreboard (STARTING phase).
     *
     * @param players          all participants
     * @param remainingSeconds seconds remaining until race start
     */
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

    /**
     * Initializes the player scoreboards for all participants at the start of the race.
     *
     * @param players    all participants
     * @param serverName the server name displayed as the scoreboard title
     */
    public void setup(Collection<PlayerData> players, String serverName) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            setupPlayerBoard(pd, p, serverName);
        }
    }

    /**
     * Sets up the scoreboard for a single player (also used for reconnecting players).
     *
     * @param pd         the player's data
     * @param p          the online player instance
     * @param serverName the server name displayed as the scoreboard title
     */
    public void setupPlayerBoard(PlayerData pd, Player p, String serverName) {
        Scoreboard sb = getOrCreateBoard(pd, p);
        p.setScoreboard(sb);

        // Remove all l-teams from the STARTING phase
        for (Team t : new ArrayList<>(sb.getTeams())) t.unregister();
        for (String e : sb.getEntries()) sb.resetScores(e);

        // Remove old objectives and re-register them
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
     * Updates sidebar and tab scores for all players during the running race.
     * @param sorted     player list sorted descending by distance
     * @param allPlayers all participants (UUID → PlayerData)
     * @param gm         reference to the GameManager for live positions and timer
     */
    public void update(List<PlayerData> sorted, Map<UUID, PlayerData> allPlayers, GameManager gm) {
        for (PlayerData viewerData : allPlayers.values()) {
            Player viewer = Bukkit.getPlayer(viewerData.getUuid());
            if (viewer == null) continue;

            Scoreboard sb  = boards.get(viewerData.getUuid());
            Objective  obj = sidebarObjs.get(viewerData.getUuid());
            Objective  tab = tabObjs.get(viewerData.getUuid());
            if (sb == null || obj == null) continue;

            // ── Rebuild sidebar ──────────────────────────────────────────

            // Remove only sidebar teams (prefix "l"), tab scores remain
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

            // ── Tab scores: update all players on this board ───────
            if (tab != null) {
                for (int i = 0; i < sorted.size(); i++) {
                    PlayerData pd = sorted.get(i);
                    // Score = distance; higher = further up in the tab list
                    tab.getScore(pd.getName()).setScore((int) Math.max(0, pd.getFinalDistance()));
                }
            }
        }
    }

    // ── Lobby board (IDLE) ────────────────────────────────────────────────────

    /**
     * Updates the lobby scoreboards for all currently online players.
     * Shows setup hints (build cage, open server) or the waiting status.
     * @param gm reference to the GameManager for cage and server status
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

    // ── End board (ENDED) ─────────────────────────────────────────────────────

    /**
     * Called once when the game ends – builds the end scoreboards for all participants.
     *
     * @param sorted     player list sorted descending by distance
     * @param allPlayers all participants (UUID → PlayerData)
     * @param serverName the server name displayed as the scoreboard title
     */
    public void setupEnded(List<PlayerData> sorted, Map<UUID, PlayerData> allPlayers, String serverName) {
        // Convert game boards into end boards
        for (PlayerData pd : allPlayers.values()) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;
            setupPlayerBoard(pd, p, serverName);
        }
    }

    /**
     * Called by the lobby task to keep end scoreboards up to date.
     *
     * @param sorted     player list sorted descending by distance
     * @param allPlayers all participants (UUID → PlayerData)
     * @param gm         reference to the GameManager for timer and state
     */
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

    /** Resets all players to the main scoreboard and discards the lobby boards. */
    public void clearLobbyBoards() {
        for (Map.Entry<UUID, Scoreboard> e : lobbyBoards.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        lobbyBoards.clear();
        lobbyObjs.clear();
    }

    /**
     * Removes a single player from the scoreboard system (e.g. disconnect during countdown).
     *
     * @param pd the player data of the player to remove
     */
    public void removePlayer(PlayerData pd) {
        boards.remove(pd.getUuid());
        sidebarObjs.remove(pd.getUuid());
        tabObjs.remove(pd.getUuid());
    }

    /**
     * Resets all specified players to the main scoreboard
     * and clears all internal scoreboard maps.
     * @param players the players to remove
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

    // ── Helper methods ─────────────────────────────────────────────────────────

    /**
     * Writes a line into the sidebar using a team prefix.
     * @param sb      the viewer's scoreboard
     * @param obj     the sidebar objective
     * @param score   line number (from top: higher = further up)
     * @param content text to display
     * @return {@code score - 1} for the next line
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
     * Returns the 1-based rank of a player in the sorted list.
     * @param sorted player list sorted by distance
     * @param uuid   UUID of the player to look up
     * @return rank (1 = leader), or {@code sorted.size()} if not found
     */
    private int getRank(List<PlayerData> sorted, UUID uuid) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getUuid().equals(uuid)) return i + 1;
        }
        return sorted.size();
    }

    /**
     * Calculates the current forward distance of a living player from the start line.
     * Falls back to {@link PlayerData#getFinalDistance()} if the player is offline.
     */
    private double liveDistance(PlayerData pd, GameManager gm) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p == null || gm.getStartLocation() == null) return pd.getFinalDistance();
        return gm.getDirection().getForwardDistance(
            gm.getStartLocation().getX(), gm.getStartLocation().getZ(),
            p.getLocation().getX(),       p.getLocation().getZ());
    }

    /** Formats a distance with no decimal places (minimum 0). */
    private String fmt(double d)     { return String.format("%.0f", Math.max(0, d)); }
    /** Formats a lateral deviation with sign. */
    private String fmtDev(double d)  { return (d >= 0 ? "+" : "") + String.format("%.0f", d); }
    /** Truncates a string to {@code max} characters and appends "…" if necessary. */
    private String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
}
