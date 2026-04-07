package de.flyingfinger.minecraft.deathrun;

/**
 * Beschreibt den aktuellen Zustand eines Deathrun-Spiels.
 * <ul>
 *   <li>{@link #IDLE}     – kein aktives Spiel, Lobby läuft</li>
 *   <li>{@link #STARTING} – Countdown läuft, Spieler stehen im Käfig</li>
 *   <li>{@link #RUNNING}  – Rennen ist aktiv</li>
 *   <li>{@link #ENDED}    – Spiel beendet, Ergebnisse werden angezeigt</li>
 * </ul>
 */
public enum GameState {
    IDLE,
    STARTING,
    RUNNING,
    ENDED
}
