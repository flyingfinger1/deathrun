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
 * Creates, opens, closes, and removes the glass start cage in the game world.
 * Saves a block snapshot before building so the original world
 * can be restored by {@link #removeCage(Set)}.
 */
public class CageBuilder {

    /** Creates a new CageBuilder instance. */
    public CageBuilder() {}

    private static final int FLOOR_DEPTH  = 3; // floor depth in blocks
    private static final int CAGE_SCALE   = 3; // cage is 3x as wide as cage-radius

    /**
     * Effective radius in blocks (for external calculation of the measurement point).
     *
     * @param baseRadius the configured cage radius
     * @return the effective radius in blocks ({@code baseRadius * CAGE_SCALE})
     */
    public static int getEffectiveRadius(int baseRadius) { return baseRadius * CAGE_SCALE; }

    private final Set<Location>           builtLocations       = new HashSet<>();
    private final Set<Location>           indicatorLocations   = new HashSet<>();
    private final Set<Location>           lastRemovedLocations = new HashSet<>();
    /** Snapshot of world blocks before building – used for restoration in removeCage. */
    private final Map<Location, BlockData> snapshot            = new HashMap<>();
    /** Player foot level (cy) – used as fallback in removeCage without snapshot. */
    private int cageBaseY = 0;

    /**
     * Returns the blocks removed during the last {@link #openCage()} call.
     *
     * @return a copy of the last removed locations
     */
    public Set<Location> getLastRemovedLocations() { return new HashSet<>(lastRemovedLocations); }

    /**
     * Builds a glass cage at the start position.
     * <ul>
     *   <li>Snapshot of existing blocks is saved beforehand (for /dr removecage).</li>
     *   <li>Floor: Reinforced Deepslate, {@value FLOOR_DEPTH} blocks deep</li>
     *   <li>Walls: plain glass</li>
     *   <li>Wall in run direction: lime glass (direction indicator)</li>
     *   <li>Ceiling: plain glass</li>
     * </ul>
     *
     * @param center the cage centre (player foot position)
     * @param radius the configured cage radius (effective width = radius * {@value CAGE_SCALE})
     * @param dir    the run direction (determines which wall gets the lime glass indicator)
     * @return all built block positions (used for block protection)
     */
    public Set<Location> buildCage(Location center, int radius, RunDirection dir) {
        builtLocations.clear();
        indicatorLocations.clear();
        lastRemovedLocations.clear();

        World world  = center.getWorld();
        int   cx     = center.getBlockX();
        int   cy     = center.getBlockY(); // player foot level
        int   cz     = center.getBlockZ();
        int   r      = radius * CAGE_SCALE; // 3x wider
        int   height = 4;                   // inner height
        cageBaseY = cy;

        // 0. Save snapshot of all affected blocks (before any changes)
        snapshot.clear();
        for (int y = cy + height + 1; y >= cy - FLOOR_DEPTH; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    snapshot.put(b.getLocation(), b.getBlockData().clone());
                }
            }
        }

        // 1. Fill entire volume top-to-bottom with air first (no physics),
        //    so vegetation (grass, flowers, etc.) does not disappear as a drop.
        for (int y = cy + height + 1; y >= cy - FLOOR_DEPTH; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        // 2. Remove item drops and mobs in the area
        Location areaCenter = new Location(world, cx + 0.5, cy + height / 2.0, cz + 0.5);
        for (Entity e : world.getNearbyEntities(areaCenter, r + 2, height + 4, r + 2)) {
            if (e instanceof Item) { e.remove(); continue; }
            if (e instanceof LivingEntity && !(e instanceof Player)) e.remove();
        }

        // 3. Build cage
        // Floor: FLOOR_DEPTH blocks deep, Reinforced Deepslate
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = 1; dy <= FLOOR_DEPTH; dy++) {
                    setBlock(world, x, cy - dy, z, Material.REINFORCED_DEEPSLATE);
                }
                // Ceiling
                setBlock(world, x, cy + height, z, Material.GLASS);
            }
        }

        // Walls (border only, full height from cy to cy+height-1)
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

    /**
     * Determines the material for a wall position.
     * The wall in the run direction receives the direction indicator material (lime glass),
     * all other walls use plain glass.
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
     * Sets a block in the world, registers it in {@code builtLocations}
     * and in {@code indicatorLocations} if needed.
     */
    private void setBlock(World world, int x, int y, int z, Material mat) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(mat);
        Location loc = b.getLocation();
        builtLocations.add(loc);
        if (mat == Material.LIME_STAINED_GLASS) indicatorLocations.add(loc);
    }

    /** Removes the lime glass wall (start direction indicator) so players can begin running. */
    public void openCage() {
        lastRemovedLocations.clear();
        lastRemovedLocations.addAll(indicatorLocations);
        for (Location loc : indicatorLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        builtLocations.removeAll(indicatorLocations);
        indicatorLocations.clear();
    }

    /** Restores the lime glass wall (after /dr stop). */
    public void closeCage() {
        for (Location loc : lastRemovedLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.LIME_STAINED_GLASS);
        }
        indicatorLocations.addAll(lastRemovedLocations);
        builtLocations.addAll(lastRemovedLocations);
    }

    /**
     * Changes the direction indicator (lime glass wall) to a new direction,
     * without rebuilding the entire cage.
     * Called after /dr setdirection when the cage is already built.
     *
     * @param cx     cage centre X coordinate
     * @param cy     cage centre Y coordinate (player foot level)
     * @param cz     cage centre Z coordinate
     * @param radius the configured cage radius
     * @param newDir the new run direction
     */
    public void changeDirection(int cx, int cy, int cz, int radius, RunDirection newDir) {
        if (builtLocations.isEmpty()) return;

        World world = builtLocations.iterator().next().getWorld();
        if (world == null) return;

        int r      = radius * CAGE_SCALE;
        int height = 4;

        // Old indicator wall → plain glass
        for (Location loc : indicatorLocations) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.GLASS);
        }
        builtLocations.removeAll(indicatorLocations);
        indicatorLocations.clear();

        // New indicator wall → lime glass
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

        // Reset lastRemovedLocations since the new wall is now the current one
        lastRemovedLocations.clear();
        lastRemovedLocations.addAll(indicatorLocations);
    }

    /**
     * Restores the internal state after a server restart.
     * The blocks already exist in the world – only the location sets
     * are recalculated, without changing any world content.
     * No snapshot is available after restart, so {@link #removeCage(Set)} falls back
     * to the grass/dirt/air fallback.
     *
     * @param spawnCenter the cage centre (spawn location stored in config)
     * @param radius      the configured cage radius
     * @param dir         the run direction (to identify indicator locations)
     * @return the reconstructed set of all cage block positions
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

        // Floor + ceiling
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = 1; dy <= FLOOR_DEPTH; dy++) {
                    builtLocations.add(world.getBlockAt(x, cy - dy, z).getLocation());
                }
                builtLocations.add(world.getBlockAt(x, cy + height, z).getLocation());
            }
        }

        // Walls (border only)
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

        // lastRemovedLocations = current indicator positions (for closeCage after stop)
        lastRemovedLocations.addAll(indicatorLocations);
        return new HashSet<>(builtLocations);
    }

    /**
     * Removes all blocks built by the plugin and restores the original world.
     * If a snapshot is available, the exact original blocks are restored.
     * Otherwise a grass/dirt/air fallback is applied.
     *
     * @param locations the set of cage block positions to clear
     */
    public void removeCage(Set<Location> locations) {
        if (!snapshot.isEmpty()) {
            // Restore original state
            for (Map.Entry<Location, BlockData> entry : snapshot.entrySet()) {
                Location loc = entry.getKey();
                if (loc.getWorld() != null) {
                    loc.getBlock().setBlockData(entry.getValue(), false);
                }
            }
            snapshot.clear();
        } else {
            // Fallback (no snapshot after restart):
            // Floor → grass/dirt, everything else → air
            for (Location loc : locations) {
                if (loc.getWorld() == null) continue;
                int dy = cageBaseY - loc.getBlockY(); // >0 = below player level
                Material mat = switch (dy) {
                    case 1  -> Material.GRASS_BLOCK; // directly below player level
                    case 2, 3 -> Material.DIRT;      // below that
                    default -> Material.AIR;          // walls, ceiling, interior
                };
                loc.getBlock().setType(mat, false);
            }
        }
        builtLocations.clear();
        indicatorLocations.clear();
        lastRemovedLocations.clear();
    }
}
