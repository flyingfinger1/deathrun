package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Aktualisiert nur den angezeigten Namen in der Tabliste (playerListName).
 * Sortierung läuft über das PLAYER_LIST-Objective im SidebarManager.
 */
public class TablistManager {

    public void update(List<PlayerData> sorted, GameManager gm) {
        for (int i = 0; i < sorted.size(); i++) {
            PlayerData pd   = sorted.get(i);
            Player     p    = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;

            double dist   = pd.isAlive() ? liveDistance(pd, gm) : pd.getFinalDistance();
            String status = Messages.str(pd.isAlive() ? "sidebar.game.icon-alive" : "sidebar.game.icon-dead");
            p.playerListName(LegacyComponentSerializer.legacySection().deserialize(
                Messages.str("tablist.entry", i + 1, p.getName(), fmt(dist), status)
            ));
        }
    }

    public void reset(Collection<PlayerData> players) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.playerListName(null);
        }
    }

    private double liveDistance(PlayerData pd, GameManager gm) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p == null || gm.getStartLocation() == null) return 0;
        return gm.getDirection().getForwardDistance(
            gm.getStartLocation().getX(), gm.getStartLocation().getZ(),
            p.getLocation().getX(),       p.getLocation().getZ());
    }

    private String fmt(double d) { return String.format("%.0f", Math.max(0, d)); }
}
