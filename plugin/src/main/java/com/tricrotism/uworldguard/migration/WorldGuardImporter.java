package com.tricrotism.uworldguard.migration;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Reads an existing WorldGuard installation's YAML region files
 * ({@code plugins/WorldGuard/worlds/<world>/regions.yml}) and imports them into a
 * {@link RegionManager}. WorldGuard's flag names are mirrored by {@link Flags}, so flags
 * round-trip by name; cuboid, poly2d and global regions are supported (cylinder/sphere are
 * uWorldGuard-only and never appear in WorldGuard data). Per-flag region groups
 * ({@code <flag>-group}) and legacy name-based domain entries are not migrated.
 *
 * <p>Pure parsing + in-memory population — no Bukkit world access — so it is safe to run on
 * the async scheduler. The caller decides persistence (mark dirty, save on cycle).
 */
@NullMarked
public final class WorldGuardImporter {

    /**
     * Outcome of importing one world. {@code conflicts} lists the ids that already existed and
     * were left untouched (only populated when not overwriting); {@code skipped} counts regions
     * whose shape WorldGuard supports but uWorldGuard does not parse from this format.
     */
    public record Result(int imported, List<String> conflicts, int skipped) {
    }

    private final File worldsDir;

    /**
     * @param pluginsDir the server's {@code plugins} directory (the data folder's parent)
     */
    public WorldGuardImporter(final File pluginsDir) {
        this.worldsDir = new File(new File(pluginsDir, "WorldGuard"), "worlds");
    }

    public boolean hasData(final String worldName) {
        return regionsFile(worldName).exists();
    }

    /**
     * Import the WorldGuard regions for {@code worldName} into {@code manager}.
     *
     * @param overwrite replace regions whose id already exists; otherwise existing ids are
     *                  reported as conflicts and left untouched
     * @return the per-world outcome, or {@code null} if WorldGuard has no data for this world
     */
    public @Nullable Result importWorld(
        final String worldName, final RegionManager manager, final boolean overwrite
    ) throws Exception {
        final File file = regionsFile(worldName);
        if (!file.exists()) {
            return null;
        }

        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file.toPath()));
        final ConfigurationSection root = yaml.getConfigurationSection("regions");
        if (root == null) {
            return new Result(0, List.of(), 0);
        }

        int imported = 0;
        int skipped = 0;
        final List<String> conflicts = new ArrayList<>();
        final Map<String, String> parents = new HashMap<>();

        for (final String id : root.getKeys(false)) {
            final ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }

            final ProtectedRegion region = buildRegion(id, sec);
            if (region == null) {
                skipped++;
                continue;
            }

            if (manager.hasRegion(region.getId()) && !overwrite) {
                conflicts.add(region.getId());
                continue;
            }

            region.setPriority(sec.getInt("priority", 0));
            readDomain(sec.getConfigurationSection("owners"), region.getOwners());
            readDomain(sec.getConfigurationSection("members"), region.getMembers());
            readFlags(sec.getConfigurationSection("flags"), region);
            manager.addRegion(region);
            imported++;

            final String parent = sec.getString("parent");
            if (parent != null) {
                parents.put(region.getId().toLowerCase(Locale.ROOT), parent);
            }
        }

        for (final Map.Entry<String, String> entry : parents.entrySet()) {
            final ProtectedRegion child = manager.getRegion(entry.getKey());
            final ProtectedRegion parent = manager.getRegion(entry.getValue());
            if (child != null && parent != null) {
                try {
                    child.setParent(parent);
                } catch (final IllegalArgumentException _) {
                }
            }
        }

        return new Result(imported, conflicts, skipped);
    }

    private @Nullable ProtectedRegion buildRegion(final String id, final ConfigurationSection sec) {
        final String type = sec.getString("type", "cuboid").toLowerCase(Locale.ROOT);
        switch (type) {
            case "cuboid" -> {
                final BlockVector3 min = readVec(sec.getConfigurationSection("min"));
                final BlockVector3 max = readVec(sec.getConfigurationSection("max"));
                if (min == null || max == null) {
                    return null;
                }
                return new ProtectedCuboidRegion(id, min, max);
            }
            case "poly2d" -> {
                final List<BlockVector3> points = readPoints(sec);
                if (points.size() < 3) {
                    return null;
                }
                return new ProtectedPolygonRegion(
                    id, points, floor(sec.getDouble("min-y")), floor(sec.getDouble("max-y")));
            }
            case "global" -> {
                return new GlobalProtectedRegion();
            }
            default -> {
                return null;
            }
        }
    }

    private static @Nullable BlockVector3 readVec(final @Nullable ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        return BlockVector3.at(floor(sec.getDouble("x")), floor(sec.getDouble("y")), floor(sec.getDouble("z")));
    }

    private static List<BlockVector3> readPoints(final ConfigurationSection sec) {
        final List<BlockVector3> points = new ArrayList<>();
        for (final Map<?, ?> point : sec.getMapList("points")) {
            if (point.get("x") instanceof Number x && point.get("z") instanceof Number z) {
                points.add(BlockVector3.at(floor(x.doubleValue()), 0, floor(z.doubleValue())));
            }
        }
        return points;
    }

    private static void readDomain(final @Nullable ConfigurationSection sec, final DefaultDomain domain) {
        if (sec == null) {
            return;
        }
        for (final String raw : sec.getStringList("unique-ids")) {
            try {
                domain.addPlayer(UUID.fromString(raw));
            } catch (final IllegalArgumentException _) {
            }
        }
        for (final String group : sec.getStringList("groups")) {
            domain.addGroup(group);
        }
    }

    private static void readFlags(final @Nullable ConfigurationSection sec, final ProtectedRegion region) {
        if (sec == null) {
            return;
        }
        for (final String key : sec.getKeys(false)) {
            final Flag<?> flag = Flags.get(key);
            if (flag != null) {
                applyFlag(region, flag, sec.get(key));
            }
        }
    }

    private static <T> void applyFlag(final ProtectedRegion region, final Flag<T> flag, final @Nullable Object stored) {
        if (stored == null) {
            return;
        }
        final T value = flag.unmarshal(stored);
        if (value != null) {
            region.setFlag(flag, value);
        }
    }

    private static int floor(final double value) {
        return (int) Math.floor(value);
    }

    private File regionsFile(final String worldName) {
        return new File(new File(worldsDir, worldName), "regions.yml");
    }
}
