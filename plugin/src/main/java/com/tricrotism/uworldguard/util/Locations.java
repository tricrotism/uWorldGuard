package com.tricrotism.uworldguard.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Parses location flag values of the form {@code world,x,y,z} or {@code world,x,y,z,yaw,pitch}.
 */
@NullMarked
public final class Locations {

    private Locations() {}

    public static @Nullable Location parse(final String value) {
        final String[] parts = value.split(",");
        if (parts.length < 4) return null;

        final World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) return null;

        try {
            final double x = Double.parseDouble(parts[1].trim());
            final double y = Double.parseDouble(parts[2].trim());
            final double z = Double.parseDouble(parts[3].trim());
            final float yaw = parts.length >= 5 ? Float.parseFloat(parts[4].trim()) : 0f;
            final float pitch = parts.length >= 6 ? Float.parseFloat(parts[5].trim()) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (final NumberFormatException _) {
            return null;
        }
    }
}
