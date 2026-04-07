package de.flyingfinger.minecraft.deathrun;

import org.bukkit.Material;

/**
 * Cardinal direction in which players run during the deathrun.
 * Contains helper methods for distance and position calculation relative to the start line.
 */
public enum RunDirection {
    NORTH, SOUTH, EAST, WEST;

    /** How many blocks the player is from the start in the run direction (positive = forward). */
    public double getForwardDistance(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH -> startZ - curZ;  // -Z = north
            case SOUTH -> curZ - startZ;  // +Z = south
            case EAST  -> curX - startX;  // +X = east
            case WEST  -> startX - curX;  // -X = west
        };
    }

    /** Lateral deviation from the corridor center (positive = right, negative = left). */
    public double getLateralDeviation(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curX - startX;
            case EAST,  WEST  -> curZ - startZ;
        };
    }

    /** WorldBorder center X for this player (follows in the forward direction). */
    public double getBorderCenterX(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> startX;
            case EAST,  WEST  -> curX;
        };
    }

    /** WorldBorder center Z for this player (follows in the forward direction). */
    public double getBorderCenterZ(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curZ;
            case EAST,  WEST  -> startZ;
        };
    }

    /** Lang key for the localized direction name. */
    public String getLangKey() {
        return switch (this) {
            case NORTH -> "direction.north";
            case SOUTH -> "direction.south";
            case EAST  -> "direction.east";
            case WEST  -> "direction.west";
        };
    }

    /** Glass color for the cage wall in the run direction. */
    public Material getIndicatorMaterial() {
        return Material.LIME_STAINED_GLASS;
    }

    /** Default material for all non-indicator cage walls (plain glass). */
    public Material getWallMaterial() {
        return Material.GLASS;
    }
}
