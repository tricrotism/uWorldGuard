// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;

import java.util.List;

/**
 * An axis-aligned box, backed by a uWorldGuard
 * {@code com.tricrotism.uworldguard.region.ProtectedCuboidRegion}.
 */
public class ProtectedCuboidRegion extends ProtectedRegion {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("uWorldGuard");

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
        new java.util.concurrent.atomic.AtomicBoolean();

    public ProtectedCuboidRegion(final String id, final BlockVector3 pt1, final BlockVector3 pt2) {
        this(id, false, pt1, pt2);
    }

    public ProtectedCuboidRegion(final String id, final boolean transientRegion,
                                 final BlockVector3 pt1, final BlockVector3 pt2) {
        super(new com.tricrotism.uworldguard.region.ProtectedCuboidRegion(id,
            com.tricrotism.uworldguard.util.BlockVector3.at(pt1.x(), pt1.y(), pt1.z()),
            com.tricrotism.uworldguard.util.BlockVector3.at(pt2.x(), pt2.y(), pt2.z())), transientRegion);
    }

    /**
     * Internal: adopts an existing engine cuboid.
     */
    public ProtectedCuboidRegion(final com.tricrotism.uworldguard.region.ProtectedCuboidRegion backing) {
        super(backing, false);
    }

    @Override
    public RegionType getType() {
        return RegionType.CUBOID;
    }

    @Override
    public List<BlockVector2> getPoints() {
        final BlockVector3 lo = getMinimumPoint();
        final BlockVector3 hi = getMaximumPoint();
        return List.of(
            BlockVector2.at(lo.x(), lo.z()),
            BlockVector2.at(hi.x(), lo.z()),
            BlockVector2.at(hi.x(), hi.z()),
            BlockVector2.at(lo.x(), hi.z()));
    }

    @Override
    public boolean contains(final BlockVector3 pt) {
        return contains(pt.x(), pt.y(), pt.z());
    }

    @Override
    public boolean isPhysicalArea() {
        return true;
    }

    @Override
    public int volume() {
        final BlockVector3 lo = getMinimumPoint();
        final BlockVector3 hi = getMaximumPoint();
        final long x = (long) hi.x() - lo.x() + 1L;
        final long y = (long) hi.y() - lo.y() + 1L;
        final long z = (long) hi.z() - lo.z() + 1L;
        final long volume = x * y * z;
        return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
    }

    /**
     * @deprecated uWorldGuard's cuboids are immutable — the spatial index caches their bounds, so a
     * corner cannot be moved in place. Redefine the region instead. This is a no-op.
     */
    @Deprecated
    public void setMinimumPoint(final BlockVector3 position) {
        warnImmutable("setMinimumPoint");
    }

    /**
     * @deprecated see {@link #setMinimumPoint(BlockVector3)}. This is a no-op.
     */
    @Deprecated
    public void setMaximumPoint(final BlockVector3 position) {
        warnImmutable("setMaximumPoint");
    }

    private void warnImmutable(final String member) {
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.stub("ProtectedCuboidRegion." + member);
        if (com.tricrotism.uworldguard.util.VerboseLogging.enabled() && WARNED.compareAndSet(false, true)) {
            LOG.log(java.util.logging.Level.WARNING,
                "A plugin called ProtectedCuboidRegion.{0} on region ''{1}''. uWorldGuard''s cuboids"
                    + " are immutable — the call did nothing. Redefine the region instead."
                    + " See /uwg compat.",
                new Object[]{member, getId()});
        }
    }
}
