// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * Canonical shim wrappers for engine regions and region managers.
 *
 * <p>Consumers compare regions by identity and use them as map keys, so wrapping the same engine
 * region twice has to yield the same instance. The engine object holds its own wrapper — see
 * {@code ProtectedRegion.uwgCompatShim()} — so resolving one is a field read rather than a cache
 * lookup, and a deleted region takes its wrapper with it. A side cache keyed on identity did the
 * same job and cost far more: every read recorded into the cache's read buffer, and draining that
 * buffer submitted to the common pool and unparked a worker, which was the single largest slice of
 * this plugin's time on a region thread.
 *
 * <p>Applicable-region sets are deliberately <em>not</em> wrapped ahead of time: the engine builds a
 * fresh one per query and drops it immediately, so a cache would cost more than the wrapper it saves.
 */
public final class RegionAdapters {

    private RegionAdapters() {
    }

    /**
     * The shim view of an engine region, creating it on first use. {@code manager} is the world
     * manager that owns the region, or {@code null} for a detached region.
     *
     * <p>The new wrapper's own constructor publishes it through {@link #link}, which is also what
     * makes a consumer-built region canonical, so the answer is always read back off the engine
     * region rather than taken from {@link #create} — that is the instance every other caller and
     * every racing thread will see.
     */
    public static ProtectedRegion region(
        final com.tricrotism.uworldguard.region.ProtectedRegion backing, final com.tricrotism.uworldguard.region.RegionManager manager
    ) {
        ProtectedRegion shim = (ProtectedRegion) backing.uwgCompatShim();
        if (shim == null) {
            CompatDiagnostics.WRAPS.increment();
            create(backing);
            shim = (ProtectedRegion) backing.uwgCompatShim();
        }
        if (manager != null) {
            shim.uwgAttach(manager);
        }
        return shim;
    }

    /**
     * The shim view of an engine world manager, creating it on first use.
     */
    public static RegionManager manager(
        final com.tricrotism.uworldguard.region.RegionManager backing, final String worldName
    ) {
        final RegionManager shim = (RegionManager) backing.uwgCompatShim();
        if (shim != null) {
            return shim;
        }
        return (RegionManager) backing.uwgLinkCompatShim(new RegionManager(backing, worldName));
    }

    /**
     * Internal: called from the shim {@code ProtectedRegion} constructor so a consumer-built region
     * is canonical from the moment it exists.
     */
    public static void link(
        final com.tricrotism.uworldguard.region.ProtectedRegion backing, final ProtectedRegion shim
    ) {
        backing.uwgLinkCompatShim(shim);
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
