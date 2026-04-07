package de.flyingfinger.minecraft.deathrun;

import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Hält alle spielrelevanten Daten eines Teilnehmers für die Dauer eines Rennens.
 * Instanzen werden beim Spielstart angelegt und am Ende verworfen.
 */
public class PlayerData {

    private final UUID uuid;
    private final String name;
    private boolean alive        = true;
    private boolean disconnected = false;   // alive aber gerade offline
    private double  finalDistance = 0.0;
    private WorldBorder personalBorder = null;

    /**
     * Erstellt einen neuen Datensatz für den angegebenen Spieler.
     * @param player der Spieler, dessen Daten gespeichert werden
     */
    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

    /** @return eindeutige UUID des Spielers */
    public UUID getUuid() { return uuid; }
    /** @return Anzeigename des Spielers zum Zeitpunkt des Spielstarts */
    public String getName() { return name; }

    /** @return {@code true} solange der Spieler noch nicht gestorben ist */
    public boolean isAlive()         { return alive; }
    /** @param alive {@code false} markiert den Spieler als ausgeschieden */
    public void setAlive(boolean alive) { this.alive = alive; }

    /** @return {@code true} wenn der Spieler gerade offline ist, aber noch als lebendig gilt */
    public boolean isDisconnected()          { return disconnected; }
    /** @param d {@code true} setzt den Spieler auf „abwesend" (lebendig, aber offline) */
    public void setDisconnected(boolean d)   { this.disconnected = d; }

    /** @return zurückgelegte Distanz in Blöcken (endgültig nach Tod / Disconnect, sonst Live-Wert) */
    public double getFinalDistance() { return finalDistance; }
    /** @param d einzufrierende Distanz in Blöcken */
    public void setFinalDistance(double d) { this.finalDistance = d; }

    /** @return die persönliche WorldBorder dieses Spielers, oder {@code null} wenn keine gesetzt */
    public WorldBorder getPersonalBorder() { return personalBorder; }
    /** @param border die persönliche WorldBorder (darf {@code null} sein um sie zu entfernen) */
    public void setPersonalBorder(WorldBorder border) { this.personalBorder = border; }
}
