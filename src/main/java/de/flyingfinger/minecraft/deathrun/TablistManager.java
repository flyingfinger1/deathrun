package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Updates only the displayed name in the tab list (playerListName).
 * Sorting is handled via the PLAYER_LIST objective in SidebarManager.
 */
public class TablistManager {

    /** Creates a new TablistManager instance. */
    public TablistManager() {}

    /**
     * Updates the {@code playerListName} of all participants in the tab list.
     * Shows rank, name, distance, status icon, and heart display.
     * @param sorted player list sorted by distance
     * @param gm     reference to the GameManager for live positions
     */
    public void update(List<PlayerData> sorted, GameManager gm) {
        for (int i = 0; i < sorted.size(); i++) {
            PlayerData pd = sorted.get(i);
            Player     p  = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;

            double dist   = pd.isAlive() ? liveDistance(pd, gm) : pd.getFinalDistance();
            String status = Messages.str(p, pd.isAlive() ? "sidebar.game.icon-alive" : "sidebar.game.icon-dead");

            // Main entry (rank, name, distance, status icon) via legacy serializer
            Component entry = LegacyComponentSerializer.legacySection().deserialize(
                Messages.str(p, "tablist.entry", i + 1, p.getName(), fmt(dist), status)
            );

            // Append hearts as real Adventure component (♥ via Unicode, no legacy font issue)
            if (pd.isAlive()) {
                entry = entry.append(heartsComponent(p));
            }

            p.playerListName(entry);
        }
    }

    /**
     * Builds the heart display as an Adventure component.
     * Missing hearts appear as hollow ♡ (dark gray), present ones as ♥ (colored).
     * Example at 8/10 hearts: ♡♡♥♥♥♥♥♥♥♥
     * Color of full hearts: green > 6, yellow 3–6, red ≤ 3.
     */
    private Component heartsComponent(Player p) {
        double health    = p.getHealth();
        double maxHealth = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
            ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        int hearts    = (int) Math.ceil(health / 2.0);
        int maxHearts = (int) Math.ceil(maxHealth / 2.0);
        TextColor color = hearts > 6 ? NamedTextColor.GREEN : hearts > 3 ? NamedTextColor.YELLOW : NamedTextColor.RED;

        int missing = maxHearts - hearts;
        Component result = Component.text(" ");
        if (missing > 0) {
            result = result.append(Component.text("♡".repeat(missing), NamedTextColor.DARK_GRAY));
        }
        result = result.append(Component.text("♥".repeat(hearts), color));
        return result;
    }

    /**
     * Resets the {@code playerListName} of all specified players to the default.
     * @param players the participants to reset
     */
    public void reset(Collection<PlayerData> players) {
        for (PlayerData pd : players) {
            Player p = Bukkit.getPlayer(pd.getUuid());
            if (p != null) p.playerListName(null);
        }
    }

    /**
     * Calculates the current forward distance of a player from the start line.
     * Returns 0 if the player is offline or no start point has been set.
     */
    private double liveDistance(PlayerData pd, GameManager gm) {
        Player p = Bukkit.getPlayer(pd.getUuid());
        if (p == null || gm.getStartLocation() == null) return 0;
        return gm.getDirection().getForwardDistance(
            gm.getStartLocation().getX(), gm.getStartLocation().getZ(),
            p.getLocation().getX(),       p.getLocation().getZ());
    }

    /** Formats a distance with no decimal places (minimum 0). */
    private String fmt(double d) { return String.format("%.0f", Math.max(0, d)); }
}
