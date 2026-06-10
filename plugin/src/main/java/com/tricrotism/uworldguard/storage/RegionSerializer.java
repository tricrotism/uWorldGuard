package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Converts a {@link RegionManager} to and from a YAML text document. Shared by every
 * storage backend: {@link YamlRegionStore} writes the document to a file, and a SQL backend
 * stores the same document per world — so the region format is defined in exactly one place.
 */
@NullMarked
public final class RegionSerializer {

    public String toYaml(final RegionManager manager) {
        final YamlConfiguration yaml = new YamlConfiguration();
        final ConfigurationSection root = yaml.createSection("regions");
        for (final ProtectedRegion region : manager.getRegions()) {
            writeRegion(root.createSection(region.getId()), region);
        }
        return yaml.saveToString();
    }

    public void fromYaml(final String text, final RegionManager manager) throws InvalidConfigurationException {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        final ConfigurationSection root = yaml.getConfigurationSection("regions");
        if (root == null) {
            return;
        }

        final Map<String, String> parents = new HashMap<>();
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
            if (region == null) {
                continue;
            }
            region.setPriority(sec.getInt("priority", 0));
            readDomain(sec.getConfigurationSection("owners"), region.getOwners());
            readDomain(sec.getConfigurationSection("members"), region.getMembers());
            readFlags(sec.getConfigurationSection("flags"), region);
            final String parent = sec.getString("parent");
            if (parent != null) {
                parents.put(id.toLowerCase(Locale.ROOT), parent);
            }
            manager.addRegion(region);
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
        manager.clearDirty();
    }

    private @Nullable ProtectedRegion readRegion(final String id, final ConfigurationSection sec) {
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
            default -> null;
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
    }

    @SuppressWarnings("unchecked")
    private static Object marshal(final Flag<?> flag, final Object value) {
        return ((Flag<Object>) flag).marshal(value);
    }

    private void readFlags(final @Nullable ConfigurationSection sec, final ProtectedRegion region) {
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

    private void readDomain(final @Nullable ConfigurationSection sec, final DefaultDomain domain) {
        if (sec == null) {
            return;
        }
        for (final String raw : sec.getStringList("players")) {
            try {
                domain.addPlayer(UUID.fromString(raw));
            } catch (final IllegalArgumentException _) {
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
