package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.StateFlag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Convenience facade for location-based queries. Stateless over a {@link RegionContainer},
 * so a single instance is safe to share across threads.
 *
 * <p>The {@code (World, int, int, int)} method is the primitive entry point; the {@link Block},
 * {@link Entity}, and {@link Location} overloads funnel into it from coordinates the caller
 * already holds, so a query allocates nothing for the position. Prefer the {@code Block}/{@code
 * Entity} overloads in hot paths over {@code block.getLocation()} / {@code entity.getLocation()},
 * which allocate a throwaway {@link Location}.
 */
@NullMarked
public final class RegionQuery {

    private static final ApplicableRegionSet EMPTY = new ApplicableRegionSet(List.of(), null);

    private final RegionContainer container;

    public RegionQuery(final RegionContainer container) {
        this.container = container;
    }

    public ApplicableRegionSet getApplicableRegions(final World world, final int x, final int y, final int z) {
        final RegionManager manager = container.get(world);
        if (manager == null) {
            return EMPTY;
        }
        return manager.getApplicableRegions(x, y, z);
    }

    public ApplicableRegionSet getApplicableRegions(final Location location) {
        return getApplicableRegions(location.getWorld(),
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public ApplicableRegionSet getApplicableRegions(final Block block) {
        return getApplicableRegions(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public ApplicableRegionSet getApplicableRegions(final Entity entity) {
        return getApplicableRegions(entity.getWorld(),
            Location.locToBlock(entity.getX()), Location.locToBlock(entity.getY()), Location.locToBlock(entity.getZ()));
    }

    /**
     * Whether the player (or {@code null} for an unknown actor) may build at the location.
     */
    public boolean testBuild(final Location location, final @Nullable Player player) {
        return getApplicableRegions(location).canBuild(player != null ? player.getUniqueId() : null);
    }

    public boolean testState(final Location location, final StateFlag flag) {
        return getApplicableRegions(location).testState(flag);
    }

    public boolean testState(final Block block, final StateFlag flag) {
        return getApplicableRegions(block).testState(flag);
    }

    public boolean testState(final Entity entity, final StateFlag flag) {
        return getApplicableRegions(entity).testState(flag);
    }

    public <T> @Nullable T queryValue(final Location location, final Flag<T> flag) {
        return getApplicableRegions(location).queryValue(flag);
    }

    public <T> @Nullable T queryValue(final Entity entity, final Flag<T> flag) {
        return getApplicableRegions(entity).queryValue(flag);
    }
}
