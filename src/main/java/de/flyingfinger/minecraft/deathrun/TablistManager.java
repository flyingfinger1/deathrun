package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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
            PlayerData pd = sorted.get(i);
            Player     p  = Bukkit.getPlayer(pd.getUuid());
            if (p == null) continue;

            double dist   = pd.isAlive() ? liveDistance(pd, gm) : pd.getFinalDistance();
            String status = Messages.str(p, pd.isAlive() ? "sidebar.game.icon-alive" : "sidebar.game.icon-dead");

            // Haupteintrag (Rang, Name, Distanz, Status-Icon) per Legacy-Serializer
            Component entry = LegacyComponentSerializer.legacySection().deserialize(
                Messages.str(p, "tablist.entry", i + 1, p.getName(), fmt(dist), status)
            );

            // Herzen als echtes Adventure-Component anhängen (♥ via Unicode, kein Legacy-Font-Problem)
            if (pd.isAlive()) {
                entry = entry.append(heartsComponent(p));
            }

            p.playerListName(entry);
        }
    }

    /**
     * Baut die Herzanzeige als Adventure-Component.
     * Das ♥-Symbol wird als normaler Unicode-Text übergeben, nicht durch den
     * Legacy-Serializer gejagt – so rendert Minecraft es korrekt.
     * Farbe: grün > 6 Herzen, gelb 3–6, rot ≤ 3.
     */
    private Component heartsComponent(Player p) {
        double health    = p.getHealth();
        double maxHealth = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
            ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        int hearts    = (int) Math.ceil(health / 2.0);
        int maxHearts = (int) Math.ceil(maxHealth / 2.0);
        TextColor color = hearts > 6 ? NamedTextColor.GREEN : hearts > 3 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(" " + hearts + "♥", color)
            .append(Component.text("/" + maxHearts, NamedTextColor.GRAY));
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
