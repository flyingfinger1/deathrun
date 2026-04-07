package de.flyingfinger.minecraft.deathrun;

/**
 * Describes the current state of a deathrun game.
 * <ul>
 *   <li>{@link #IDLE}     – no active game, lobby is running</li>
 *   <li>{@link #STARTING} – countdown is running, players are standing in the cage</li>
 *   <li>{@link #RUNNING}  – race is active</li>
 *   <li>{@link #ENDED}    – game finished, results are being displayed</li>
 * </ul>
 */
public enum GameState {
    IDLE,
    STARTING,
    RUNNING,
    ENDED
}
