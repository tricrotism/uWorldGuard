// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;

import java.util.Collection;
import java.util.List;

/**
 * The whole-world fallback region, backed by a uWorldGuard
 * {@code com.tricrotism.uworldguard.region.GlobalProtectedRegion}.
 *
 * <p>The engine's global region is always named {@code __global__}; an {@code id} passed here is
 * ignored, matching how WorldGuard treats it in practice.
 */
public class GlobalProtectedRegion extends ProtectedRegion {

    public GlobalProtectedRegion(final String id) {
        this(id, false);
    }

    public GlobalProtectedRegion(final String id, final boolean transientRegion) {
        super(new com.tricrotism.uworldguard.region.GlobalProtectedRegion(), transientRegion);
        this.uwgRequestedId = id;
    }

    private String uwgRequestedId = com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID;

    /**
     * Internal: whether this was built under another id, WorldGuard's idiom for a template region
     * with no area. The engine has one global region per world, so adding such a region would replace
     * the real one and its flags.
     */
    public final boolean uwgIsTemplate() {
        return !com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID.equalsIgnoreCase(uwgRequestedId);
    }

    /**
     * Internal: adopts an existing engine global region.
     */
    public GlobalProtectedRegion(final com.tricrotism.uworldguard.region.GlobalProtectedRegion backing) {
        super(backing, false);
    }

    @Override
    public RegionType getType() {
        return RegionType.GLOBAL;
    }

    @Override
    public List<BlockVector2> getPoints() {
        return List.of();
    }

    @Override
    public List<ProtectedRegion> getIntersectingRegions(final Collection<ProtectedRegion> regions) {
        return List.of();
    }

    @Override
    public boolean contains(final BlockVector3 pt) {
        return true;
    }

    @Override
    public boolean isPhysicalArea() {
        return false;
    }

    @Override
    public int volume() {
        return 0;
    }
}
