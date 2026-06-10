package com.tricrotism.uworldguard.util;

import org.bukkit.Location;
import org.jspecify.annotations.NullMarked;

/**
 * Immutable integer block coordinate. Used instead of Bukkit {@link Location}
 * inside regions so containment checks allocate nothing in hot paths.
 */
@NullMarked
public record BlockVector3(int x, int y, int z) {

    public static BlockVector3 at(final int x, final int y, final int z) {
        return new BlockVector3(x, y, z);
    }

    public static BlockVector3 of(final Location location) {
        return new BlockVector3(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public BlockVector3 min(final BlockVector3 other) {
        return new BlockVector3(Math.min(x, other.x), Math.min(y, other.y), Math.min(z, other.z));
    }

    public BlockVector3 max(final BlockVector3 other) {
        return new BlockVector3(Math.max(x, other.x), Math.max(y, other.y), Math.max(z, other.z));
    }
}
