package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Convenience facade for location-based queries. Stateless over a {@link RegionContainer},
 * so a single instance is safe to share across threads.
 */
@NullMarked
public final class RegionQuery {

    private static final ApplicableRegionSet EMPTY = new ApplicableRegionSet(List.of(), null);

    private final RegionContainer container;

    public RegionQuery(final RegionContainer container) {
        this.container = container;
    }

    public ApplicableRegionSet getApplicableRegions(final Location location) {
        final RegionManager manager = container.get(location.getWorld());
        if (manager == null) {
            return EMPTY;
        }
        return manager.getApplicableRegions(BlockVector3.of(location));
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

    public <T> @Nullable T queryValue(final Location location, final Flag<T> flag) {
        return getApplicableRegions(location).queryValue(flag);
    }
}
