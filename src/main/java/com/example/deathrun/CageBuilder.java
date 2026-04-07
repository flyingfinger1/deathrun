package com.example.deathrun;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;

public class CageBuilder {

    private static final int FLOOR_DEPTH  = 3; // Boden-Tiefe in Blöcken
    private static final int CAGE_SCALE   = 3; // Käfig ist 3x so breit wie cage-radius

    /** Effektiver Radius in Blöcken (für externe Berechnung des Messpunkts). */
    public static int getEffectiveRadius(int baseRadius) { return baseRadius * CAGE_SCALE; }

    private final Set<Location> builtLocations    = new HashSet<>();
    private final Set<Location> indicatorLocations = new HashSet<>();
    private final Set<Location> lastRemovedLocations = new HashSet<>();

    /** Gibt die beim letzten openCage()-Aufruf entfernten Blöcke zurück. */
    public Set<Location> getLastRemovedLocations() { return new HashSet<>(lastRemovedLocations); }

    /**
     * Baut einen Glaskäfig an der Startposition.
     * - Boden: Reinforced Deepslate, 3 Blöcke tief
     * - Wände: normales Glas
     * - Wand in Laufrichtung: Lime-Glas
     * - Decke: normales Glas
     * Gibt alle verbauten Block-Positionen zurück (für Schutz).
     */
    public Set<Location> buildCage(Location center, int radius, RunDirection dir) {
        builtLocations.clear();
        indicatorLocations.clear();

        World world  = center.getWorld();
        int   cx     = center.getBlockX();
        int   cy     = center.getBlockY(); // Spieler-Füße-Level
        int   cz     = center.getBlockZ();
        int   r      = radius * CAGE_SCALE; // 3x breiter
        int   height = 4;                   // Innenhöhe

        // Boden: FLOOR_DEPTH Blöcke tief, Reinforced Deepslate
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = 1; dy <= FLOOR_DEPTH; dy++) {
                    setBlock(world, x, cy - dy, z, Material.REINFORCED_DEEPSLATE);
                }
                // Decke
                setBlock(world, x, cy + height, z, Material.GLASS);
            }
        }

        // Wände (nur Rand, volle Höhe von cy bis cy+height-1)
        for (int y = cy; y < cy + height; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (x != cx - r && x != cx + r &&
                        z != cz - r && z != cz + r) continue;

                    setBlock(world, x, y, z, getWallMaterial(x, z, cx, cz, r, dir));
                }
            }
        }

        // Innenraum leeren
        for (int y = cy; y < cy + height; y++) {
            for (int x = cx - r + 1; x <= cx + r - 1; x++) {
                for (int z = cz - r + 1; z <= cz + r - 1; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }

        return new HashSet<>(builtLocations);
    }

    /** Bestimmt das Material für eine Wand, je nachdem ob sie in Laufrichtung liegt. */
    private Material getWallMaterial(int x, int z, int cx, int cz, int radius, RunDirection dir) {
        boolean isIndicator = switch (dir) {
            case NORTH -> z == cz - radius;
            case SOUTH -> z == cz + radius;
            case EAST  -> x == cx + radius;
            case WEST  -> x == cx - radius;
        };
        return isIndicator ? dir.getIndicatorMaterial() : dir.getWallMaterial();
    }

    private void setBlock(World world, int x, int y, int z, Material mat) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(mat);
        Location loc = b.getLocation();
        builtLocations.add(loc);
        if (mat == Material.LIME_STAINED_GLASS) indicatorLocations.add(loc);
    }

    /** Entfernt die Lime-Glas-Wand (Startrichtungsanzeige), damit Spieler loslaufen können. */
    public void openCage() {
        lastRemovedLocations.clear();
        lastRemovedLocations.addAll(indicatorLocations);
        for (Location loc : indicatorLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        builtLocations.removeAll(indicatorLocations);
        indicatorLocations.clear();
    }

    /** Entfernt alle vom Plugin gebauten Blöcke. */
    public void removeCage(Set<Location> locations) {
        for (Location loc : locations) {
            if (loc.getWorld() != null) {
                loc.getBlock().setType(Material.AIR);
            }
        }
        builtLocations.clear();
    }
}
