package de.flyingfinger.minecraft.deathrun;

import org.bukkit.Material;

/**
 * Cardinal direction in which players run during the deathrun.
 * Contains helper methods for distance and position calculation relative to the start line.
 */
public enum RunDirection {
    /** Players run toward decreasing Z (negative Z axis). */
    NORTH,
    /** Players run toward increasing Z (positive Z axis). */
    SOUTH,
    /** Players run toward increasing X (positive X axis). */
    EAST,
    /** Players run toward decreasing X (negative X axis). */
    WEST;

    /**
     * Returns how many blocks the player is from the start in the run direction (positive = forward).
     * @param startX X coordinate of the start point
     * @param startZ Z coordinate of the start point
     * @param curX   current X coordinate of the player
     * @param curZ   current Z coordinate of the player
     * @return forward distance in blocks (positive = ahead of start)
     */
    public double getForwardDistance(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH -> startZ - curZ;  // -Z = north
            case SOUTH -> curZ - startZ;  // +Z = south
            case EAST  -> curX - startX;  // +X = east
            case WEST  -> startX - curX;  // -X = west
        };
    }

    /**
     * Returns the lateral deviation from the corridor center (positive = right, negative = left).
     * @param startX X coordinate of the start point
     * @param startZ Z coordinate of the start point
     * @param curX   current X coordinate of the player
     * @param curZ   current Z coordinate of the player
     * @return lateral deviation in blocks (positive = right, negative = left)
     */
    public double getLateralDeviation(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curX - startX;
            case EAST,  WEST  -> curZ - startZ;
        };
    }

    /**
     * Returns the WorldBorder center X coordinate for this player (follows in the forward direction).
     * @param startX X coordinate of the start point
     * @param startZ Z coordinate of the start point
     * @param curX   current X coordinate of the player
     * @param curZ   current Z coordinate of the player
     * @return WorldBorder center X coordinate
     */
    public double getBorderCenterX(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> startX;
            case EAST,  WEST  -> curX;
        };
    }

    /**
     * Returns the WorldBorder center Z coordinate for this player (follows in the forward direction).
     * @param startX X coordinate of the start point
     * @param startZ Z coordinate of the start point
     * @param curX   current X coordinate of the player
     * @param curZ   current Z coordinate of the player
     * @return WorldBorder center Z coordinate
     */
    public double getBorderCenterZ(double startX, double startZ, double curX, double curZ) {
        return switch (this) {
            case NORTH, SOUTH -> curZ;
            case EAST,  WEST  -> startZ;
        };
    }

    /**
     * Returns the lang key for the localized direction name.
     * @return message key for this direction's localized name
     */
    public String getLangKey() {
        return switch (this) {
            case NORTH -> "direction.north";
            case SOUTH -> "direction.south";
            case EAST  -> "direction.east";
            case WEST  -> "direction.west";
        };
    }

    /**
     * Returns the glass color used for the cage wall facing the run direction.
     * @return indicator material for the run-direction cage wall
     */
    public Material getIndicatorMaterial() {
        return Material.LIME_STAINED_GLASS;
    }

    /**
     * Returns the default material for all non-indicator cage walls (plain glass).
     * @return material used for the remaining cage walls
     */
    public Material getWallMaterial() {
        return Material.GLASS;
    }
}
