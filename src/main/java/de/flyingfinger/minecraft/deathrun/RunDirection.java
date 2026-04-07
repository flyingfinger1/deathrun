package de.flyingfinger.minecraft.deathrun;

import org.bukkit.Material;

public enum RunDirection {
    NORTH, SOUTH, EAST, WEST;

    /** Wie viele Blöcke der Spieler in Laufrichtung vom Start entfernt ist (positiv = vorwärts). */
    public double getForwardDistance(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH -> startZ - curZ;  // -Z = Norden
            case SOUTH -> curZ - startZ;  // +Z = Süden
            case EAST  -> curX - startX;  // +X = Osten
            case WEST  -> startX - curX;  // -X = Westen
        };
    }

    /** Seitliche Abweichung vom Korridor-Zentrum (positiv = rechts, negativ = links). */
    public double getLateralDeviation(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curX - startX;
            case EAST,  WEST  -> curZ - startZ;
        };
    }

    /** WorldBorder-Zentrum-X für diesen Spieler (folgt in Vorwärtsrichtung). */
    public double getBorderCenterX(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> startX;
            case EAST,  WEST  -> curX;
        };
    }

    /** WorldBorder-Zentrum-Z für diesen Spieler (folgt in Vorwärtsrichtung). */
    public double getBorderCenterZ(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curZ;
            case EAST,  WEST  -> startZ;
        };
    }

    public String getDisplayName() {
        return switch (this) {
            case NORTH -> "Norden";
            case SOUTH -> "Süden";
            case EAST  -> "Osten";
            case WEST  -> "Westen";
        };
    }

    /** Glasfarbe für die Käfig-Wand in Laufrichtung. */
    public Material getIndicatorMaterial() {
        return Material.LIME_STAINED_GLASS;
    }

    public Material getWallMaterial() {
        return Material.GLASS;
    }
}
