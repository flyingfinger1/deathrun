package de.flyingfinger.minecraft.deathrun;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Haupt-Plugin-Klasse. Initialisiert alle Subsysteme, registriert
 * den Befehlshandler sowie den Event-Listener und startet den Lobby-Task.
 */
public class DeathrunPlugin extends JavaPlugin implements Listener {

    private GameManager gameManager;

    /** Wird beim Aktivieren des Plugins aufgerufen. Lädt Konfiguration und startet alle Subsysteme. */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        Messages.load(this, getConfig().getString("language", "de"));
        gameManager = new GameManager(this);

        var cmd = getCommand("dr");
        if (cmd != null) {
            DeathrunCommand handler = new DeathrunCommand(gameManager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(new GameListener(gameManager, this), this);
        getServer().getPluginManager().registerEvents(this, this);

        // Lobby-Scoreboard starten (1 Tick verzögert, damit alle Systeme bereit sind)
        getServer().getScheduler().runTask(this, gameManager::startLobbyTask);

        getLogger().info("Deathrun Plugin aktiviert.");
    }

    /** Wird beim Deaktivieren des Plugins aufgerufen. Stoppt laufende Tasks sauber. */
    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        getLogger().info("Deathrun Plugin deaktiviert.");
    }

    /**
     * Setzt die MOTD der Server-Liste abhängig vom aktuellen Spielzustand.
     * @param event das Ping-Event des Clients
     */
    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (gameManager == null) return;
        GameState state = gameManager.getState();
        if (!gameManager.isServerOpen() && state == GameState.IDLE) {
            event.motd(Messages.comp("motd.maintenance"));
        } else if (state == GameState.STARTING) {
            event.motd(Messages.comp("motd.countdown"));
        } else if (state == GameState.RUNNING) {
            event.motd(Messages.comp("motd.running"));
        } else if (state == GameState.ENDED) {
            event.motd(Messages.comp("motd.ended"));
        }
    }

    /** @return die zentrale {@link GameManager}-Instanz dieses Plugins */
    public GameManager getGameManager() { return gameManager; }
}
