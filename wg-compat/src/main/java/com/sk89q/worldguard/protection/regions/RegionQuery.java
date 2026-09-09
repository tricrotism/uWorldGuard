// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.MapFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;

import java.util.*;

/**
 * Location-based queries against uWorldGuard's region engine.
 *
 * <p>Stateless, so one instance is safe to share across region threads. Each query resolves the
 * world's engine manager and asks it for the applicable set; a world whose regions are not loaded
 * yields an empty set rather than {@code null}.
 */
public class RegionQuery {

    public RegionQuery() {
    }

    /**
     * How the regions of a query result should be shaped before they are handed back.
     */
    public enum QueryOption {

        /**
         * Whatever order the index produced.
         */
        NONE,

        /**
         * Priority descending, highest first.
         */
        SORT,

        /**
         * Priority descending, with every region's parent chain pulled in.
         */
        COMPUTE_PARENTS;

        public List<ProtectedRegion> constructResult(final Set<ProtectedRegion> applicable) {
            if (this == NONE) {
                return new ArrayList<>(applicable);
            }
            final List<ProtectedRegion> result = new ArrayList<>(applicable.size());
            if (this == SORT) {
                result.addAll(applicable);
            } else {
                final Set<ProtectedRegion> withParents = new LinkedHashSet<>(applicable);
                for (final ProtectedRegion region : applicable) {
                    for (ProtectedRegion parent = region.getParent(); parent != null;
                         parent = parent.getParent()) {
                        if (!withParents.add(parent)) {
                            break;
                        }
                    }
                }
                result.addAll(withParents);
            }
            result.sort(null);
            return result;
        }
    }

    /**
     * The applicable regions at a location, re-shaped by {@code option}. uWorldGuard already answers
     * priority-descending, so only {@link QueryOption#COMPUTE_PARENTS} rebuilds the set — the other
     * two keep the engine-backed result and its fast query path.
     */
    public ApplicableRegionSet getApplicableRegions(final Location location, final QueryOption option) {
        final ApplicableRegionSet set = getApplicableRegions(location);
        if (option != QueryOption.COMPUTE_PARENTS) {
            return set;
        }
        return uwgWithParents(set);
    }

    /**
     * Internal: rebuilds an engine-backed set as a plain list including every parent chain.
     */
    public static ApplicableRegionSet uwgWithParents(final ApplicableRegionSet set) {
        final ProtectedRegion global = set instanceof com.tricrotism.uworldguard.wgcompat.WrappedRegionSet wrapped
            ? wrapped.uwgGlobalRegion()
            : null;
        return new com.tricrotism.uworldguard.wgcompat.ListRegionSet(
            QueryOption.COMPUTE_PARENTS.constructResult(set.getRegions()), global);
    }

    public ApplicableRegionSet getApplicableRegions(final Location location) {
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.QUERIES.increment();
        final org.bukkit.World world = worldOf(location);
        final com.tricrotism.uworldguard.region.RegionManager manager =
            world == null || !com.tricrotism.uworldguard.wgcompat.WgCompatBridge.active() ? null
                : com.tricrotism.uworldguard.wgcompat.WgCompatBridge.container().get(world);
        if (manager == null) {
            return new com.tricrotism.uworldguard.wgcompat.ListRegionSet(List.of(), null);
        }
        return new com.tricrotism.uworldguard.wgcompat.WrappedRegionSet(
            manager.getApplicableRegions(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
            manager);
    }

    public StateFlag.State queryState(
        final Location location, final RegionAssociable associable, final StateFlag... flags
    ) {
        return getApplicableRegions(location).queryState(associable, flags);
    }

    public boolean testState(
        final Location location, final RegionAssociable associable, final StateFlag... flag
    ) {
        return getApplicableRegions(location).testState(associable, flag);
    }

    public boolean testBuild(
        final Location location, final RegionAssociable associable, final StateFlag... flag
    ) {
        return testBuild(getApplicableRegions(location), associable, flag);
    }

    public <K> boolean testBuild(
        final Location location, final RegionAssociable associable,
        final MapFlag<K, StateFlag.State> mapFlag, final K key,
        final StateFlag fallback, final StateFlag... flag
    ) {
        final ApplicableRegionSet set = getApplicableRegions(location);
        final StateFlag.State mapped = set.queryMapValue(associable, mapFlag, key, fallback);
        if (mapped != null) {
            return mapped == StateFlag.State.ALLOW;
        }
        return testBuild(set, associable, flag);
    }

    public <V> V queryValue(final Location location, final RegionAssociable associable, final Flag<V> flag) {
        return getApplicableRegions(location).queryValue(associable, flag);
    }

    public <V> Collection<V> queryAllValues(
        final Location location, final RegionAssociable associable, final Flag<V> flag
    ) {
        return getApplicableRegions(location).queryAllValues(associable, flag);
    }

    public <V, K> V queryMapValue(
        final Location location, final RegionAssociable associable, final MapFlag<K, V> flag, final K key
    ) {
        return getApplicableRegions(location).queryMapValue(associable, flag, key);
    }

    public <V, K> V queryMapValue(
        final Location location, final RegionAssociable associable, final MapFlag<K, V> flag, final K key, final Flag<V> fallback
    ) {
        return getApplicableRegions(location).queryMapValue(associable, flag, key, fallback);
    }

    public StateFlag.State queryState(
        final Location location, final LocalPlayer player, final StateFlag... flags
    ) {
        return getApplicableRegions(location).queryState(player, flags);
    }

    public boolean testState(final Location location, final LocalPlayer player, final StateFlag... flag) {
        return getApplicableRegions(location).testState(player, flag);
    }

    public boolean testBuild(final Location location, final LocalPlayer player, final StateFlag... flag) {
        return testBuild(location, (RegionAssociable) player, flag);
    }

    public <V> V queryValue(final Location location, final LocalPlayer player, final Flag<V> flag) {
        return getApplicableRegions(location).queryValue(player, flag);
    }

    public <V> Collection<V> queryAllValues(
        final Location location, final LocalPlayer player, final Flag<V> flag
    ) {
        return getApplicableRegions(location).queryAllValues(player, flag);
    }

    /**
     * WorldGuard combines the build result with the queried flags after converting its {@code DENY}
     * to nothing, so an explicit {@code allow} on one of the flags opens the region to non-members
     * while an explicit {@code deny} still refuses members. Testing membership first instead would
     * make that {@code allow} inert, which is not what a plugin querying this API expects.
     */
    private static boolean testBuild(
        final ApplicableRegionSet set, final RegionAssociable associable, final StateFlag... flag
    ) {
        if (flag.length == 0) {
            return canBuild(set, associable);
        }
        final StateFlag.State state = set.queryState(associable, flag);
        if (state == StateFlag.State.DENY) {
            return false;
        }
        return state == StateFlag.State.ALLOW || canBuild(set, associable);
    }

    private static boolean canBuild(final ApplicableRegionSet set, final RegionAssociable associable) {
        if (set instanceof com.tricrotism.uworldguard.wgcompat.WrappedRegionSet wrapped) {
            return wrapped.canBuild(associable);
        }
        return ((com.tricrotism.uworldguard.wgcompat.ListRegionSet) set).canBuild(associable);
    }

    private static org.bukkit.World worldOf(final Location location) {
        final com.sk89q.worldedit.extent.Extent extent = location.getExtent();
        if (extent instanceof com.sk89q.worldedit.world.World world) {
            return BukkitAdapter.adapt(world);
        }
        return null;
    }
}
