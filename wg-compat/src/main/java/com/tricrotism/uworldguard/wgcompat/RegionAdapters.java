// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * Canonical shim wrappers for engine regions and region managers.
 *
 * <p>Consumers compare regions by identity and use them as map keys, so wrapping the same engine
 * region twice has to yield the same instance. Caffeine's {@code weakKeys().weakValues()} gives that
 * with identity comparison and without pinning a deleted region alive.
 *
 * <p>Applicable-region sets are deliberately <em>not</em> cached: the engine builds a fresh one per
 * query and drops it immediately, so a cache would cost more than the wrapper it saves.
 */
public final class RegionAdapters {

    private static final Cache<com.tricrotism.uworldguard.region.ProtectedRegion, ProtectedRegion> REGIONS =
        Caffeine.newBuilder().weakKeys().weakValues().build();

    private static final Cache<com.tricrotism.uworldguard.region.RegionManager, RegionManager> MANAGERS =
        Caffeine.newBuilder().weakKeys().weakValues().build();

    private RegionAdapters() {
    }

    /**
     * The shim view of an engine region, creating it on first use. {@code manager} is the world
     * manager that owns the region, or {@code null} for a detached region.
     */
    public static ProtectedRegion region(
        final com.tricrotism.uworldguard.region.ProtectedRegion backing, final com.tricrotism.uworldguard.region.RegionManager manager
    ) {
        final ProtectedRegion existing = REGIONS.getIfPresent(backing);
        if (existing != null) {
            if (manager != null) {
                existing.uwgAttach(manager);
            }
            return existing;
        }
        CompatDiagnostics.WRAPS.increment();
        final ProtectedRegion shim = create(backing);
        final ProtectedRegion canonical = REGIONS.getIfPresent(backing);
        final ProtectedRegion result = canonical != null ? canonical : shim;
        if (manager != null) {
            result.uwgAttach(manager);
        }
        return result;
    }

    /**
     * The shim view of an engine world manager, creating it on first use.
     */
    public static RegionManager manager(
        final com.tricrotism.uworldguard.region.RegionManager backing, final String worldName
    ) {
        return MANAGERS.get(backing, key -> new RegionManager(key, worldName));
    }

    /**
     * Internal: called from the shim {@code ProtectedRegion} constructor so a consumer-built region
     * is canonical from the moment it exists.
     */
    public static void link(
        final com.tricrotism.uworldguard.region.ProtectedRegion backing, final ProtectedRegion shim
    ) {
        REGIONS.asMap().putIfAbsent(backing, shim);
    }

    private static ProtectedRegion create(final com.tricrotism.uworldguard.region.ProtectedRegion backing) {
        if (backing instanceof com.tricrotism.uworldguard.region.GlobalProtectedRegion global) {
            return new GlobalProtectedRegion(global);
        }
        if (backing instanceof com.tricrotism.uworldguard.region.ProtectedCuboidRegion cuboid) {
            return new ProtectedCuboidRegion(cuboid);
        }
        return new ProtectedPolygonalRegion(backing);
    }
}
