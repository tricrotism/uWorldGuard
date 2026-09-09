// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.*;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * WorldGuard's flag resolution, run over shim regions.
 *
 * <p>The engine resolves flags itself and far more cheaply, but only for a subject it can identify
 * by UUID. A consumer that passes its own {@code RegionAssociable} — one that is not a
 * {@link UuidSubject} — has to be asked what it associates as, so resolution is redone here: highest
 * priority wins, {@code DENY} beats {@code ALLOW} within a priority, group-qualified values that do
 * not cover the subject are skipped, then the global region, then the flag default.
 *
 * <p>Every {@code regions} list passed in must already be sorted by priority descending; the
 * early-out depends on it.
 */
public final class FlagQueryAlgorithms {

    private FlagQueryAlgorithms() {
    }

    public static StateFlag.State queryState(
        final List<ProtectedRegion> regions,
        final ProtectedRegion global,
        final Association association,
        final StateFlag flag
    ) {
        boolean found = false;
        int bestPriority = 0;
        StateFlag.State result = null;
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (found && region.getPriority() < bestPriority) {
                break;
            }
            if (!appliesTo(region, flag, association)) {
                continue;
            }
            final StateFlag.State value = region.getFlag(flag);
            if (value == null) {
                continue;
            }
            if (!found) {
                found = true;
                bestPriority = region.getPriority();
                result = value;
            } else if (value == StateFlag.State.DENY) {
                result = StateFlag.State.DENY;
            }
        }
        if (found) {
            return result;
        }
        if (global != null && appliesTo(global, flag, association)) {
            final StateFlag.State value = global.getFlag(flag);
            if (value != null) {
                return value;
            }
        }
        return flag.getDefault();
    }

    public static <V> V queryValue(
        final List<ProtectedRegion> regions,
        final ProtectedRegion global,
        final Association association,
        final Flag<V> flag
    ) {
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (!appliesTo(region, flag, association)) {
                continue;
            }
            final V value = region.getFlag(flag);
            if (value != null) {
                return value;
            }
        }
        if (global != null && appliesTo(global, flag, association)) {
            final V value = global.getFlag(flag);
            if (value != null) {
                return value;
            }
        }
        return flag.getDefault();
    }

    public static <V> Collection<V> queryAllValues(
        final List<ProtectedRegion> regions,
        final ProtectedRegion global,
        final Association association,
        final Flag<V> flag
    ) {
        final List<V> values = new ArrayList<>(4);
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (!appliesTo(region, flag, association)) {
                continue;
            }
            final V value = region.getFlag(flag);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty() && global != null && appliesTo(global, flag, association)) {
            final V value = global.getFlag(flag);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * WorldGuard's build check: {@code passthrough} regions are transparent, an explicit
     * {@code build} value on the highest-priority stack wins, otherwise membership of that stack
     * decides, otherwise the presence of any region means protected.
     */
    public static boolean canBuild(
        final List<ProtectedRegion> regions,
        final ProtectedRegion global,
        final Association association
    ) {
        ProtectedRegion top = null;
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (region.getFlag(Flags.PASSTHROUGH) != StateFlag.State.ALLOW) {
                top = region;
                break;
            }
        }
        if (top == null) {
            return global == null || global.getFlag(Flags.BUILD) != StateFlag.State.DENY;
        }

        final int topPriority = top.getPriority();
        StateFlag.State explicit = null;
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (region.getFlag(Flags.PASSTHROUGH) == StateFlag.State.ALLOW) {
                continue;
            }
            if (region.getPriority() != topPriority) {
                break;
            }
            if (!appliesTo(region, Flags.BUILD, association)) {
                continue;
            }
            final StateFlag.State value = region.getFlag(Flags.BUILD);
            if (value != null) {
                explicit = explicit == StateFlag.State.DENY ? StateFlag.State.DENY : value;
            }
        }
        if (explicit != null) {
            return explicit == StateFlag.State.ALLOW;
        }
        return association == Association.OWNER || association == Association.MEMBER;
    }

    /**
     * What {@code subject} associates as across {@code regions}. Resolved once per query, as
     * WorldGuard does, rather than per region.
     */
    public static Association association(final RegionAssociable subject, final List<ProtectedRegion> regions) {
        if (subject == null) {
            return Association.NON_MEMBER;
        }
        final Association association = subject.getAssociation(regions);
        return association == null ? Association.NON_MEMBER : association;
    }

    private static boolean appliesTo(
        final ProtectedRegion region, final Flag<?> flag, final Association association
    ) {
        final RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        if (groupFlag == null) {
            return true;
        }
        final RegionGroup group = region.getFlag(groupFlag);
        return group == null || group.contains(association);
    }
}
