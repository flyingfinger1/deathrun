package de.flyingfinger.minecraft.deathrun;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Erstellt, öffnet, schließt und entfernt den Glas-Startkäfig in der Spielwelt.
 * Speichert einen Block-Snapshot vor dem Bau, damit die ursprüngliche Welt
 * bei {@link #removeCage(java.util.Set)} wiederhergestellt werden kann.
 */
public class CageBuilder {

    private static final int FLOOR_DEPTH  = 3; // Boden-Tiefe in Blöcken
    private static final int CAGE_SCALE   = 3; // Käfig ist 3x so breit wie cage-radius

    /** Effektiver Radius in Blöcken (für externe Berechnung des Messpunkts). */
    public static int getEffectiveRadius(int baseRadius) { return baseRadius * CAGE_SCALE; }

    private final Set<Location>           builtLocations       = new HashSet<>();
    private final Set<Location>           indicatorLocations   = new HashSet<>();
    private final Set<Location>           lastRemovedLocations = new HashSet<>();
    /** Snapshot der Welt-Blöcke vor dem Bau – für Restaurierung bei removeCage. */
    private final Map<Location, BlockData> snapshot            = new HashMap<>();
    /** Spieler-Fußlevel (cy) – für den Fallback bei removeCage ohne Snapshot. */
    private int cageBaseY = 0;

    /** Gibt die beim letzten openCage()-Aufruf entfernten Blöcke zurück. */
    public Set<Location> getLastRemovedLocations() { return new HashSet<>(lastRemovedLocations); }

    /**
     * Baut einen Glaskäfig an der Startposition.
     * - Snapshot der bestehenden Blöcke wird vorab gespeichert (für /dr removecage).
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
        cageBaseY = cy;

        // 0. Snapshot aller betroffenen Blöcke speichern (vor jeder Veränderung)
        snapshot.clear();
        for (int y = cy + height + 1; y >= cy - FLOOR_DEPTH; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    snapshot.put(b.getLocation(), b.getBlockData().clone());
                }
            }
        }

        // 1. Gesamtes Volumen vorab von oben nach unten mit Air füllen (keine Physics),
        //    damit Vegetation (Gras, Blumen, etc.) nicht als Drop verschwindet.
        for (int y = cy + height + 1; y >= cy - FLOOR_DEPTH; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        // 2. Item-Drops und Mobs im Bereich entfernen
        Location areaCenter = new Location(world, cx + 0.5, cy + height / 2.0, cz + 0.5);
        for (Entity e : world.getNearbyEntities(areaCenter, r + 2, height + 4, r + 2)) {
            if (e instanceof Item) { e.remove(); continue; }
            if (e instanceof LivingEntity && !(e instanceof Player)) e.remove();
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
    /**
     * Bestimmt das Material für eine Wandposition.
     * Die Wand in Laufrichtung erhält das Richtungs-Indikatormaterial (Lime-Glas),
     * alle anderen Wände normales Glas.
     */
    private Material getWallMaterial(int x, int z, int cx, int cz, int radius, RunDirection dir) {
        boolean isIndicator = switch (dir) {
            case NORTH -> z == cz - radius;
            case SOUTH -> z == cz + radius;
            case EAST  -> x == cx + radius;
            case WEST  -> x == cx - radius;
        };
        return isIndicator ? dir.getIndicatorMaterial() : dir.getWallMaterial();
    }

    /**
     * Setzt einen Block in der Welt, registriert ihn in {@code builtLocations}
     * und bei Bedarf in {@code indicatorLocations}.
     */
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

    /**
     * Ändert die Richtungsanzeige (Lime-Glas-Wand) auf eine neue Richtung,
     * ohne den gesamten Käfig neu zu bauen.
     * Wird nach /dr setdirection aufgerufen, wenn der Käfig bereits steht.
     */
    public void changeDirection(int cx, int cy, int cz, int radius, RunDirection newDir) {
        if (builtLocations.isEmpty()) return;

        World world = builtLocations.iterator().next().getWorld();
        if (world == null) return;

        int r      = radius * CAGE_SCALE;
        int height = 4;

        // Alte Indicator-Wand → normales Glas
        for (Location loc : indicatorLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.GLASS);
        }
        builtLocations.removeAll(indicatorLocations);
        indicatorLocations.clear();

        // Neue Indicator-Wand → Lime-Glas
        for (int y = cy; y < cy + height; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (x != cx - r && x != cx + r && z != cz - r && z != cz + r) continue;
                    boolean isIndicator = switch (newDir) {
                        case NORTH -> z == cz - r;
                        case SOUTH -> z == cz + r;
                        case EAST  -> x == cx + r;
                        case WEST  -> x == cx - r;
                    };
                    if (isIndicator) {
                        Block b = world.getBlockAt(x, y, z);
                        b.setType(newDir.getIndicatorMaterial());
                        Location loc = b.getLocation();
                        indicatorLocations.add(loc);
                        builtLocations.add(loc);
                    }
                }
            }
        }

        // lastRemovedLocations zurücksetzen, da die neue Wand jetzt die aktuelle ist
        lastRemovedLocations.clear();
        lastRemovedLocations.addAll(indicatorLocations);
    }

    /**
     * Stellt den internen Zustand nach einem Server-Neustart wieder her.
     * Die Blöcke existieren bereits in der Welt – es werden nur die Location-Sets
     * neu berechnet, ohne irgendetwas am Weltinhalt zu ändern.
     * Kein Snapshot verfügbar nach Neustart → removeCage() fällt auf AIR-Fallback zurück.
     */
    public Set<Location> restoreState(Location spawnCenter, int radius, RunDirection dir) {
        builtLocations.clear();
        indicatorLocations.clear();
        lastRemovedLocations.clear();
        snapshot.clear();

        World world = spawnCenter.getWorld();
        if (world == null) return new HashSet<>();

        int cx     = spawnCenter.getBlockX();
        int cy     = spawnCenter.getBlockY();
        int cz     = spawnCenter.getBlockZ();
        int r      = radius * CAGE_SCALE;
        int height = 4;
        cageBaseY  = cy;

        // Boden + Decke
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = 1; dy <= FLOOR_DEPTH; dy++) {
                    builtLocations.add(world.getBlockAt(x, cy - dy, z).getLocation());
                }
                builtLocations.add(world.getBlockAt(x, cy + height, z).getLocation());
            }
        }

        // Wände (nur Rand)
        for (int y = cy; y < cy + height; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (x != cx - r && x != cx + r && z != cz - r && z != cz + r) continue;
                    Location loc = world.getBlockAt(x, y, z).getLocation();
                    builtLocations.add(loc);
                    boolean isIndicator = switch (dir) {
                        case NORTH -> z == cz - r;
                        case SOUTH -> z == cz + r;
                        case EAST  -> x == cx + r;
                        case WEST  -> x == cx - r;
                    };
                    if (isIndicator) indicatorLocations.add(loc);
                }
            }
        }

        // lastRemovedLocations = aktuelle Indicator-Positionen (für closeCage nach Stop)
        lastRemovedLocations.addAll(indicatorLocations);
        return new HashSet<>(builtLocations);
    }

    /**
     * Entfernt alle vom Plugin gebauten Blöcke und restauriert die ursprüngliche Welt.
     * Wenn ein Snapshot vorhanden ist, werden die Originalblöcke wiederhergestellt.
     */
    public void removeCage(Set<Location> locations) {
        if (!snapshot.isEmpty()) {
            // Originalzustand wiederherstellen
            for (Map.Entry<Location, BlockData> entry : snapshot.entrySet()) {
                Location loc = entry.getKey();
                if (loc.getWorld() != null) {
                    loc.getBlock().setBlockData(entry.getValue(), false);
                }
            }
            snapshot.clear();
        } else {
            // Fallback (kein Snapshot nach Neustart):
            // Boden → Gras/Erde, alles andere → Luft
            for (Location loc : locations) {
                if (loc.getWorld() == null) continue;
                int dy = cageBaseY - loc.getBlockY(); // >0 = unterhalb Spielerlevel
                Material mat = switch (dy) {
                    case 1  -> Material.GRASS_BLOCK; // direkt unter Spielerlevel
                    case 2, 3 -> Material.DIRT;      // darunter
                    default -> Material.AIR;          // Wände, Decke, Innenraum
                };
                loc.getBlock().setType(mat, false);
            }
        }
        builtLocations.clear();
        indicatorLocations.clear();
        lastRemovedLocations.clear();
    }
}
