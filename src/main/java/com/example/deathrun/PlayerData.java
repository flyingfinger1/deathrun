package com.example.deathrun;

import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private final String name;
    private boolean alive        = true;
    private boolean disconnected = false;   // alive aber gerade offline
    private double  finalDistance = 0.0;
    private WorldBorder personalBorder = null;

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }

    public boolean isAlive()         { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }

    public boolean isDisconnected()          { return disconnected; }
    public void setDisconnected(boolean d)   { this.disconnected = d; }

    public double getFinalDistance() { return finalDistance; }
    public void setFinalDistance(double d) { this.finalDistance = d; }

    public WorldBorder getPersonalBorder() { return personalBorder; }
    public void setPersonalBorder(WorldBorder border) { this.personalBorder = border; }
}
