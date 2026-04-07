package com.example.deathrun;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class DeathrunPlugin extends JavaPlugin implements Listener {

    private GameManager gameManager;

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

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        getLogger().info("Deathrun Plugin deaktiviert.");
    }

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

    public GameManager getGameManager() { return gameManager; }
}
