package de.flyingfinger.minecraft.deathrun;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

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
        lastRemovedLocations.clear();

        World world  = center.getWorld();
        int   cx     = center.getBlockX();
        int   cy     = center.getBlockY(); // Spieler-Füße-Level
        int   cz     = center.getBlockZ();
        int   r      = radius * CAGE_SCALE; // 3x breiter
        int   height = 4;                   // Innenhöhe

        // 1. Gesamtes Volumen vorab von oben nach unten mit Air füllen (keine Physics),
        //    damit Vegetation (Gras, Blumen, etc.) nicht als Drop verschwindet.
        for (int y = cy + height + 1; y >= cy - FLOOR_DEPTH; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        // 2. Item-Drops im Bereich entfernen
        Location areaCenter = new Location(world, cx + 0.5, cy + height / 2.0, cz + 0.5);
        for (Entity e : world.getNearbyEntities(areaCenter, r + 2, height + 4, r + 2)) {
            if (e instanceof Item) e.remove();
        }

        // 3. Käfig aufbauen
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

    /** Stellt die Lime-Glas-Wand wieder her (nach /dr stop). */
    public void closeCage() {
        for (Location loc : lastRemovedLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.LIME_STAINED_GLASS);
        }
        indicatorLocations.addAll(lastRemovedLocations);
        builtLocations.addAll(lastRemovedLocations);
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
