// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;

import java.util.Map;

/**
 * Internal: converts between a WorldEdit {@link Location} and uWorldGuard's
 * {@code world,x,y,z,yaw,pitch} storage string. Loaded only when a location flag is actually used,
 * so WorldEdit stays off the class-initialization path of {@link Flags}.
 *
 * <p>Not part of the WorldGuard API.
 */
public final class LocationCodec {

    private LocationCodec() {
    }

    /**
     * The WorldEdit world by that name, or {@code null} when no world of that name is loaded.
     */
    public static com.sk89q.worldedit.world.World worldByName(final String name) {
        final org.bukkit.World world = name == null ? null : org.bukkit.Bukkit.getWorld(name);
        return world == null ? null : BukkitAdapter.adapt(world);
    }

    public static Location fromString(final String raw) {
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(parts[0].trim());
        if (world == null) {
            return null;
        }
        try {
            final double x = Double.parseDouble(parts[1].trim());
            final double y = Double.parseDouble(parts[2].trim());
            final double z = Double.parseDouble(parts[3].trim());
            final float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0.0f;
            final float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0.0f;
            return new Location(BukkitAdapter.adapt(world), x, y, z, yaw, pitch);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public static Location fromMap(final Map<?, ?> map) {
        final Object world = map.get("world");
        if (world == null) {
            return null;
        }
        return fromString(world + ","
            + number(map, "x") + ","
            + number(map, "y") + ","
            + number(map, "z") + ","
            + number(map, "yaw") + ","
            + number(map, "pitch"));
    }

    private static Object number(final Map<?, ?> map, final String key) {
        final Object value = map.get(key);
        return value == null ? Integer.valueOf(0) : value;
    }

    public static String toStorageString(final Location location) {
        final String world = worldName(location);
        if (world == null) {
            return null;
        }
        return world + ","
            + location.getX() + ","
            + location.getY() + ","
            + location.getZ() + ","
            + location.getYaw() + ","
            + location.getPitch();
    }

    public static String worldName(final Location location) {
        return location.getExtent() instanceof com.sk89q.worldedit.world.World world ? world.getName() : null;
    }
}
