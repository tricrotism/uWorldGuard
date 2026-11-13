// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection;

import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.MapFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.tricrotism.uworldguard.wgcompat.FlagQueryAlgorithms;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * WorldGuard's flag resolution over an explicit list of regions.
 *
 * <p>Consumers build one of these from regions they already hold, usually the result of a query, to
 * resolve flags without going back through {@code RegionQuery}. Resolution is the same walk the rest
 * of the shim uses ({@link FlagQueryAlgorithms}): highest priority wins, {@code DENY} beats
 * {@code ALLOW} within a priority, group-qualified values that do not cover the subject are skipped,
 * then the global region, then the flag's default.
 *
 * <p>{@code regions} must be sorted by priority descending, as {@code NormativeOrders.sort} leaves
 * them. The list is read, never copied or modified.
 */
public class FlagValueCalculator {

    private final List<ProtectedRegion> regions;
    private final ProtectedRegion globalRegion;

    public FlagValueCalculator(final List<ProtectedRegion> regions, final ProtectedRegion globalRegion) {
        this.regions = regions;
        this.globalRegion = globalRegion;
    }

    /**
     * Whether {@code subject} is a member of the regions that decide building here. Passthrough
     * regions are ignored.
     *
     * @return {@link Result#NO_REGIONS} when every region is passthrough, {@link Result#SUCCESS} for
     * a member or owner, {@link Result#FAIL} otherwise
     */
    public Result getMembership(final RegionAssociable subject) {
        boolean anyProtecting = false;
        for (int i = 0, n = regions.size(); i < n; i++) {
            if (regions.get(i).uwgInheritedFlag(Flags.PASSTHROUGH) != StateFlag.State.ALLOW) {
                anyProtecting = true;
                break;
            }
        }
        if (!anyProtecting) {
            return Result.NO_REGIONS;
        }
        final Association association = FlagQueryAlgorithms.association(subject, regions);
        return association == Association.OWNER || association == Association.MEMBER
            ? Result.SUCCESS
            : Result.FAIL;
    }

    public StateFlag.State queryState(final RegionAssociable subject, final StateFlag... flags) {
        final Association association = FlagQueryAlgorithms.association(subject, regions);
        StateFlag.State result = null;
        for (int i = 0; i < flags.length; i++) {
            final StateFlag.State state =
                FlagQueryAlgorithms.queryState(regions, globalRegion, association, flags[i]);
            if (state == StateFlag.State.DENY) {
                return StateFlag.State.DENY;
            }
            if (state == StateFlag.State.ALLOW) {
                result = StateFlag.State.ALLOW;
            }
        }
        return result;
    }

    public StateFlag.State queryState(final RegionAssociable subject, final StateFlag flag) {
        return FlagQueryAlgorithms.queryState(regions, globalRegion,
            FlagQueryAlgorithms.association(subject, regions), flag);
    }

    /**
     * A state flag resolves as {@link #queryState(RegionAssociable, StateFlag)} does, so {@code DENY}
     * still beats {@code ALLOW} at equal priority.
     */
    @SuppressWarnings("unchecked")
    public <V> V queryValue(final RegionAssociable subject, final Flag<V> flag) {
        if (flag instanceof StateFlag stateFlag) {
            return (V) queryState(subject, stateFlag);
        }
        return FlagQueryAlgorithms.queryValue(regions, globalRegion,
            FlagQueryAlgorithms.association(subject, regions), flag);
    }

    /**
     * The value mapped to {@code key} by the highest-priority region whose map has that key, then the
     * global region's, then {@code fallback} resolved as an ordinary flag when it is not null.
     */
    public <V, K> V queryMapValue(
        final RegionAssociable subject, final MapFlag<K, V> flag, final K key, final Flag<V> fallback
    ) {
        final Association association = FlagQueryAlgorithms.association(subject, regions);
        for (int i = 0, n = regions.size(); i < n; i++) {
            final V value = mappedValue(regions.get(i), flag, key, association);
            if (value != null) {
                return value;
            }
        }
        if (globalRegion != null) {
            final V value = mappedValue(globalRegion, flag, key, association);
            if (value != null) {
                return value;
            }
        }
        return fallback == null ? null
            : FlagQueryAlgorithms.queryValue(regions, globalRegion, association, fallback);
    }

    public <V, K> V getEffectiveMapValue(
        final ProtectedRegion region, final MapFlag<K, V> flag, final K key, final RegionAssociable subject
    ) {
        return getEffectiveMapValueOf(region, flag, key, subject);
    }

    /**
     * The value {@code region} maps to {@code key}, inherited from the nearest parent that maps it
     * when the region does not.
     */
    public static <V, K> V getEffectiveMapValueOf(
        final ProtectedRegion region, final MapFlag<K, V> flag, final K key, final RegionAssociable subject
    ) {
        final Association association = FlagQueryAlgorithms.association(subject, List.of(region));
        for (ProtectedRegion current = region; current != null; current = current.getParent()) {
            final V value = mappedValue(current, flag, key, association);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public <V> Collection<V> queryAllValues(final RegionAssociable subject, final Flag<V> flag) {
        return FlagQueryAlgorithms.queryAllValues(regions, globalRegion,
            FlagQueryAlgorithms.association(subject, regions), flag);
    }

    public int getPriority(final ProtectedRegion region) {
        return region == globalRegion ? Integer.MIN_VALUE : getPriorityOf(region);
    }

    /**
     * A region's priority for resolution. The global region ranks below every other region
     * whatever its stored priority.
     */
    public static int getPriorityOf(final ProtectedRegion region) {
        return region instanceof GlobalProtectedRegion ? Integer.MIN_VALUE : region.getPriority();
    }

    public <V> V getEffectiveFlag(final ProtectedRegion region, final Flag<V> flag, final RegionAssociable subject) {
        return getEffectiveFlagOf(region, flag, subject);
    }

    /**
     * {@code region}'s own value for {@code flag}, or the nearest parent's when the region leaves it
     * unset. A value whose group qualifier does not cover {@code subject} counts as unset.
     */
    public static <V> V getEffectiveFlagOf(
        final ProtectedRegion region, final Flag<V> flag, final RegionAssociable subject
    ) {
        final Association association = FlagQueryAlgorithms.association(subject, List.of(region));
        for (ProtectedRegion current = region; current != null; current = current.getParent()) {
            final V value = current.getFlag(flag);
            if (value != null && FlagQueryAlgorithms.appliesTo(current, flag, association)) {
                return value;
            }
        }
        return null;
    }

    private static <V, K> V mappedValue(
        final ProtectedRegion region, final MapFlag<K, V> flag, final K key, final Association association
    ) {
        if (!FlagQueryAlgorithms.appliesTo(region, flag, association)) {
            return null;
        }
        final Map<K, V> map = region.uwgInheritedFlag(flag);
        return map == null ? null : map.get(key);
    }

    /**
     * The outcome of {@link #getMembership(RegionAssociable)}.
     */
    public enum Result {
        NO_REGIONS,
        FAIL,
        SUCCESS
    }
}
