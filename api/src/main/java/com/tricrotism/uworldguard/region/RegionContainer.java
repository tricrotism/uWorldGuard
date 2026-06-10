package com.tricrotism.uworldguard.region;

import org.bukkit.World;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for other plugins. Obtain the instance via
 * {@code com.tricrotism.uworldguard.UWorldGuardApi.regionContainer()}, or from Bukkit's
 * services manager:
 *
 * <pre>{@code
 * RegionContainer container = Bukkit.getServicesManager().load(RegionContainer.class);
 * RegionQuery query = container.createQuery();
 * boolean canBuild = query.testBuild(location, player);
 * }</pre>
 */
@NullMarked
public interface RegionContainer {

    /**
     * The region manager for a world, or {@code null} if its regions are not loaded.
     */
    @Nullable RegionManager get(World world);

    /**
     * A reusable query facade over this container.
     */
    RegionQuery createQuery();
}
