package de.flyingfinger.minecraft.deathrun;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class. Initializes all subsystems, registers
 * the command handler and event listener, and starts the lobby task.
 */
public class DeathrunPlugin extends JavaPlugin implements Listener {

    /** Creates the plugin instance. Called by the Bukkit plugin loader. */
    public DeathrunPlugin() {}

    private GameManager gameManager;

    /** Called when the plugin is enabled. Loads configuration and starts all subsystems. */
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

        // Start lobby scoreboard (delayed by 1 tick so all systems are ready)
        getServer().getScheduler().runTask(this, gameManager::startLobbyTask);

        getLogger().info("Deathrun Plugin aktiviert.");
    }

    /** Called when the plugin is disabled. Cleanly stops running tasks. */
    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        getLogger().info("Deathrun Plugin deaktiviert.");
    }

    /**
     * Sets the server list MOTD based on the current game state.
     * @param event the client ping event
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

    /**
     * Returns the central {@link GameManager} instance of this plugin.
     * @return the central {@link GameManager} instance of this plugin
     */
    public GameManager getGameManager() { return gameManager; }
}
