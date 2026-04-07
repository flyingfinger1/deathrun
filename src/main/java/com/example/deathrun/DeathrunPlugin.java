package com.example.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class DeathrunPlugin extends JavaPlugin implements Listener {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            event.motd(Component.text("§c[Wartung] §7DeathRun wird eingerichtet...", NamedTextColor.WHITE));
        } else if (state == GameState.STARTING) {
            event.motd(Component.text("§e[Countdown] §7DeathRun startet gleich...", NamedTextColor.WHITE));
        } else if (state == GameState.RUNNING) {
            event.motd(Component.text("§a[Läuft] §7DeathRun ist im Gange!", NamedTextColor.WHITE));
        } else if (state == GameState.ENDED) {
            event.motd(Component.text("§6[Ende] §7DeathRun beendet – Ergebnisse verfügbar", NamedTextColor.WHITE));
        }
    }

    public GameManager getGameManager() { return gameManager; }
}
