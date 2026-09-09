package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.logging.Logger;

/**
 * Converts a {@link RegionManager} to and from a YAML text document. Shared by every
 * storage backend: {@link YamlRegionStore} writes the document to a file, and a SQL backend
 * stores the same document per world — so the region format is defined in exactly one place.
 */
@NullMarked
public final class RegionSerializer {

    /**
     * Path separator for the region document. Bukkit's default is {@code .}, which it splits paths
     * on — so a region id containing one would be written as a nested section and read back as a
     * different, geometry-less region. Ids are validated on the way in, but the document is also
     * hand-editable and takes imports from WorldGuard, so the format itself refuses to split: no
     * character here can appear in a YAML key.
     */
    private static final char SEPARATOR = (char) 0;

    /**
     * A parent link that cannot be honored is dropped rather than failing the world's load, so the
     * regions that are fine still protect. Dropping it silently is what makes that dangerous: the
     * child then inherits none of the parent's flags, which reads in game as protection that stopped
     * working, with nothing anywhere saying why.
     */
    private static final Logger LOG = Logger.getLogger("uWorldGuard");

    public String toYaml(final RegionManager manager) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        final ConfigurationSection root = yaml.createSection("regions");
        for (final ProtectedRegion region : manager.getRegions()) {
            writeRegion(root.createSection(region.getId()), region);
        }
        return yaml.saveToString();
    }

    public void fromYaml(final String text, final RegionManager manager) throws InvalidConfigurationException {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        yaml.loadFromString(text);
        final ConfigurationSection root = yaml.getConfigurationSection("regions");
        if (root == null) {
            return;
        }

        final Map<String, String> parents = new HashMap<>();
        final Map<String, Integer> droppedFlags = new TreeMap<>();
        for (final String id : root.getKeys(false)) {
            final ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }

            final ProtectedRegion region;
            try {
                region = readRegion(id, sec);
            } catch (final RuntimeException e) {
                throw new InvalidConfigurationException("Malformed region '" + id + "'", e);
            }
            region.setPriority(sec.getInt("priority", 0));
            readDomain(sec.getConfigurationSection("owners"), region.getOwners(), id, "owners");
            readDomain(sec.getConfigurationSection("members"), region.getMembers(), id, "members");
            readFlags(sec.getConfigurationSection("flags"), region, droppedFlags);
            final String parent = sec.getString("parent");
            if (parent != null) {
                parents.put(id.toLowerCase(Locale.ROOT), parent);
            }
            manager.addRegion(region);
        }

        for (final Map.Entry<String, String> entry : parents.entrySet()) {
            final ProtectedRegion child = manager.getRegion(entry.getKey());
            if (child == null) {
                continue;
            }
            final ProtectedRegion parent = manager.getRegion(entry.getValue());
            if (parent == null) {
                LOG.warning("Region '" + entry.getKey() + "' names a parent that does not exist: '"
                    + entry.getValue() + "'. It inherits nothing until the parent is created or the"
                    + " name is corrected.");
                continue;
            }
            try {
                child.setParent(parent);
            } catch (final IllegalArgumentException e) {
                LOG.warning("Region '" + entry.getKey() + "' cannot have '" + entry.getValue()
                    + "' as its parent: " + e.getMessage() + ". It inherits nothing until the loop is"
                    + " broken.");
            }
        }
        if (!droppedFlags.isEmpty()) {
            LOG.warning("Dropped " + droppedFlags.values().stream().mapToInt(Integer::intValue).sum()
                + " stored flag value(s) this build cannot read: " + droppedFlags
                + ". The next save writes those regions without them. A flag another plugin"
                + " registers is only known once that plugin has loaded, so this can mean a missing"
                + " or late plugin rather than bad data. Check before saving over it.");
        }
        manager.clearDirty();
    }

    /**
     * A shape this build does not know is treated as a malformed region rather than skipped. Skipping
     * it read as an empty line: the region simply was not there, and because the file is rewritten
     * from what loaded, the next autosave erased it. Failing the world's load instead leaves the
     * stored file alone, which is what the caller's {@code failedLoads} guard is for.
     */
    private ProtectedRegion readRegion(final String id, final ConfigurationSection sec) {
        final String type = sec.getString("type", "cuboid");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "cuboid" -> new ProtectedCuboidRegion(id, readVec(sec, "min"), readVec(sec, "max"));
            case "polygon" -> new ProtectedPolygonRegion(id, readPoints(sec), sec.getInt("min-y"), sec.getInt("max-y"));
            case "cylinder" -> new ProtectedCylinderRegion(id,
                sec.getInt("center-x"), sec.getInt("center-z"),
                sec.getInt("radius-x"), sec.getInt("radius-z"),
                sec.getInt("min-y"), sec.getInt("max-y"));
            case "sphere" -> new ProtectedSphereRegion(id,
                sec.getInt("center-x"), sec.getInt("center-y"), sec.getInt("center-z"),
                sec.getInt("radius-x"), sec.getInt("radius-y"), sec.getInt("radius-z"));
            case "global" -> new GlobalProtectedRegion();
            default -> throw new IllegalArgumentException("unknown region type '" + type + "'");
        };
    }

    private void writeRegion(final ConfigurationSection sec, final ProtectedRegion region) {
        sec.set("type", region.getType().name().toLowerCase(Locale.ROOT));
        sec.set("priority", region.getPriority());
        final ProtectedRegion parent = region.getParent();
        if (parent != null) {
            sec.set("parent", parent.getId());
        }

        switch (region) {
            case ProtectedCuboidRegion c -> {
                writeVec(sec, "min", c.getMinimumPoint());
                writeVec(sec, "max", c.getMaximumPoint());
            }
            case ProtectedPolygonRegion p -> {
                final List<String> pts = new ArrayList<>();
                for (final BlockVector3 v : p.getPoints()) {
                    pts.add(v.x() + "," + v.z());
                }
                sec.set("points", pts);
                sec.set("min-y", p.getMinimumPoint().y());
                sec.set("max-y", p.getMaximumPoint().y());
            }
            case ProtectedCylinderRegion cy -> {
                final BlockVector3 min = cy.getMinimumPoint();
                final BlockVector3 max = cy.getMaximumPoint();
                sec.set("center-x", (min.x() + max.x()) / 2);
                sec.set("center-z", (min.z() + max.z()) / 2);
                sec.set("radius-x", (max.x() - min.x()) / 2);
                sec.set("radius-z", (max.z() - min.z()) / 2);
                sec.set("min-y", min.y());
                sec.set("max-y", max.y());
            }
            case ProtectedSphereRegion s -> {
                final BlockVector3 min = s.getMinimumPoint();
                final BlockVector3 max = s.getMaximumPoint();
                sec.set("center-x", (min.x() + max.x()) / 2);
                sec.set("center-y", (min.y() + max.y()) / 2);
                sec.set("center-z", (min.z() + max.z()) / 2);
                sec.set("radius-x", (max.x() - min.x()) / 2);
                sec.set("radius-y", (max.y() - min.y()) / 2);
                sec.set("radius-z", (max.z() - min.z()) / 2);
            }
            default -> { /* global has no geometry */ }
        }

        writeDomain(sec.createSection("owners"), region.getOwners());
        writeDomain(sec.createSection("members"), region.getMembers());
        final ConfigurationSection flagSec = sec.createSection("flags");
        for (final Map.Entry<Flag<?>, Object> e : region.getFlags().entrySet()) {
            flagSec.set(e.getKey().getName(), marshal(e.getKey(), e.getValue()));
        }
        for (final Map.Entry<Flag<?>, RegionGroup> e : region.getFlagGroups().entrySet()) {
            flagSec.set(e.getKey().getName() + "-group", e.getValue().serialized());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object marshal(final Flag<?> flag, final Object value) {
        return ((Flag<Object>) flag).marshal(value);
    }

    /**
     * @param dropped collects what could not be kept, counted by flag name, for the caller to report
     */
    private void readFlags(
        final @Nullable ConfigurationSection sec, final ProtectedRegion region,
        final Map<String, Integer> dropped
    ) {
        if (sec == null) {
            return;
        }
        for (final String key : sec.getKeys(false)) {
            if (key.endsWith("-group")) {
                final Flag<?> flag = Flags.get(key.substring(0, key.length() - "-group".length()));
                final Object raw = sec.get(key);
                if (flag == null || raw == null) {
                    continue;
                }
                final RegionGroup group = RegionGroup.parse(String.valueOf(raw));
                if (group == null) {
                    dropped.merge(key + " (unreadable group)", 1, Integer::sum);
                    continue;
                }
                region.setFlagGroup(flag, group);
                continue;
            }
            final Flag<?> flag = Flags.get(key);
            if (flag == null) {
                dropped.merge(key + " (no such flag)", 1, Integer::sum);
                continue;
            }
            if (!applyFlag(region, flag, sec.get(key))) {
                dropped.merge(key + " (unreadable value)", 1, Integer::sum);
            }
        }
    }

    /**
     * @return whether the stored value was kept; {@code false} means the flag is now unset on this
     * region and the next save writes it out that way
     */
    private static <T> boolean applyFlag(
        final ProtectedRegion region, final Flag<T> flag, final @Nullable Object stored
    ) {
        if (stored == null) {
            return true;
        }
        final T value = flag.unmarshal(stored);
        if (value == null) {
            return false;
        }
        region.setFlag(flag, value);
        return true;
    }

    /**
     * Entries that are not UUIDs are dropped, and saying so is the point: the region is written back
     * from the domain this builds, so the next autosave persists the loss. Silently, an owner stops
     * owning their region and the only record that they ever did is gone one save cycle later.
     */
    private void readDomain(
        final @Nullable ConfigurationSection sec, final DefaultDomain domain,
        final String regionId, final String role
    ) {
        if (sec == null) {
            return;
        }
        for (final String raw : sec.getStringList("players")) {
            try {
                domain.addPlayer(UUID.fromString(raw));
            } catch (final IllegalArgumentException _) {
                LOG.warning("Region '" + regionId + "' has an entry in its " + role
                    + " that is not a UUID: '" + raw + "'. It has been dropped, and the next save"
                    + " will write the region without it. Restore it from a backup if that player"
                    + " should still be listed.");
            }
        }
        for (final String group : sec.getStringList("groups")) {
            domain.addGroup(group);
        }
    }

    private void writeDomain(final ConfigurationSection sec, final DefaultDomain domain) {
        final List<String> players = new ArrayList<>();
        for (final UUID uuid : domain.getPlayers()) {
            players.add(uuid.toString());
        }
        sec.set("players", players);
        sec.set("groups", new ArrayList<>(domain.getGroups()));
    }

    private BlockVector3 readVec(final ConfigurationSection sec, final String key) {
        final List<Integer> v = sec.getIntegerList(key);
        return BlockVector3.at(v.get(0), v.get(1), v.get(2));
    }

    private void writeVec(final ConfigurationSection sec, final String key, final BlockVector3 v) {
        sec.set(key, List.of(v.x(), v.y(), v.z()));
    }

    private List<BlockVector3> readPoints(final ConfigurationSection sec) {
        final List<BlockVector3> points = new ArrayList<>();
        final int y = sec.getInt("min-y");
        for (final String raw : sec.getStringList("points")) {
            final String[] parts = raw.split(",");
            points.add(BlockVector3.at(Integer.parseInt(parts[0].trim()), y, Integer.parseInt(parts[1].trim())));
        }
        return points;
    }
}
