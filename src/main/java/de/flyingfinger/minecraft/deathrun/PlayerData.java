package de.flyingfinger.minecraft.deathrun;

import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Holds all game-relevant data of a participant for the duration of a race.
 * Instances are created at game start and discarded at the end.
 */
public class PlayerData {

    private final UUID uuid;
    private final String name;
    private boolean alive        = true;
    private boolean disconnected = false;   // alive but currently offline
    private double  finalDistance = 0.0;
    private WorldBorder personalBorder = null;

    /**
     * Creates a new data record for the specified player.
     * @param player the player whose data is being stored
     */
    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

    /**
     * Returns the unique UUID of the player.
     * @return unique UUID of the player
     */
    public UUID getUuid() { return uuid; }
    /**
     * Returns the display name of the player at the time the game started.
     * @return display name of the player at the time the game started
     */
    public String getName() { return name; }

    /**
     * Returns whether the player is still alive (has not yet died).
     * @return {@code true} as long as the player has not yet died
     */
    public boolean isAlive()         { return alive; }
    /**
     * Marks the player as alive or eliminated.
     * @param alive {@code false} marks the player as eliminated
     */
    public void setAlive(boolean alive) { this.alive = alive; }

    /**
     * Returns whether the player is currently offline but still counts as alive.
     * @return {@code true} if the player is currently offline but still counts as alive
     */
    public boolean isDisconnected()          { return disconnected; }
    /**
     * Sets the player's disconnected state.
     * @param d {@code true} sets the player to "absent" (alive but offline)
     */
    public void setDisconnected(boolean d)   { this.disconnected = d; }

    /**
     * Returns the frozen distance traveled in blocks (final after death or disconnect, otherwise live value).
     * @return distance traveled in blocks (final after death / disconnect, otherwise live value)
     */
    public double getFinalDistance() { return finalDistance; }
    /**
     * Freezes the player's distance at the given value.
     * @param d distance in blocks to freeze
     */
    public void setFinalDistance(double d) { this.finalDistance = d; }

    /**
     * Returns the personal WorldBorder assigned to this player.
     * @return the personal WorldBorder of this player, or {@code null} if none is set
     */
    public WorldBorder getPersonalBorder() { return personalBorder; }
    /**
     * Sets the personal WorldBorder for this player.
     * @param border the personal WorldBorder (may be {@code null} to remove it)
     */
    public void setPersonalBorder(WorldBorder border) { this.personalBorder = border; }
}
