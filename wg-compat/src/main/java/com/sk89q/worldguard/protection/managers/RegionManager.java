// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.managers;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import java.util.*;

/**
 * All regions for one world, backed by a uWorldGuard
 * {@code com.tricrotism.uworldguard.region.RegionManager}.
 *
 * <p>Region ids are case-insensitive: the engine keys them in lower case, so {@code getRegion} and
 * {@code hasRegion} answer regardless of the case passed in.
 *
 * <p>uWorldGuard autosaves a world whose regions are dirty, so {@link #save()} and
 * {@link #saveChanges()} only mark it — they never block and never throw.
 */
public final class RegionManager {

    private final com.tricrotism.uworldguard.region.RegionManager backing;
    private final String name;

    /**
     * Internal: use {@code com.tricrotism.uworldguard.wgcompat.RegionAdapters.manager} so wrappers
     * stay canonical.
     */
    public RegionManager(final com.tricrotism.uworldguard.region.RegionManager backing, final String name) {
        this.backing = backing;
        this.name = name;
    }

    /**
     * Internal: the engine manager this wraps.
     */
    public com.tricrotism.uworldguard.region.RegionManager uwgBacking() {
        return backing;
    }

    public String getName() {
        return name;
    }

    public ProtectedRegion getRegion(final String id) {
        final com.tricrotism.uworldguard.region.ProtectedRegion region = backing.getRegion(id);
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_READS.increment();
        return region == null ? null
            : com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(region, backing);
    }

    public boolean hasRegion(final String id) {
        return backing.hasRegion(id);
    }

    public Map<String, ProtectedRegion> getRegions() {
        final Collection<com.tricrotism.uworldguard.region.ProtectedRegion> regions = backing.getRegions();
        final Map<String, ProtectedRegion> out = new HashMap<>(Math.max(4, regions.size() * 2));
        for (final com.tricrotism.uworldguard.region.ProtectedRegion region : regions) {
            out.put(region.getId().toLowerCase(Locale.ROOT),
                com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(region, backing));
        }
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_READS.increment();
        return out;
    }

    public int size() {
        return backing.size();
    }

    public ApplicableRegionSet getApplicableRegions(final BlockVector3 position) {
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_READS.increment();
        return com.tricrotism.uworldguard.wgcompat.WrappedRegionSet.of(
            backing.getApplicableRegions(position.x(), position.y(), position.z()), backing);
    }

    /**
     * The regions whose bounding box overlaps {@code region}, highest priority first. uWorldGuard
     * indexes by bounding box, so this is a bounding-box test rather than an exact shape
     * intersection.
     */
    public ApplicableRegionSet getApplicableRegions(final ProtectedRegion region) {
        final List<ProtectedRegion> overlapping = new ArrayList<>();
        for (final com.tricrotism.uworldguard.region.ProtectedRegion candidate : backing.getRegions()) {
            final ProtectedRegion shim =
                com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(candidate, backing);
            if (shim != region && overlaps(shim, region)) {
                overlapping.add(shim);
            }
        }
        overlapping.sort(null);
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_READS.increment();
        return com.sk89q.worldguard.protection.RegionResultSet.fromSortedList(overlapping, globalRegion());
    }

    /**
     * @see RegionQuery#getApplicableRegions(com.sk89q.worldedit.util.Location, RegionQuery.QueryOption)
     */
    public ApplicableRegionSet getApplicableRegions(final BlockVector3 position,
                                                    final RegionQuery.QueryOption option) {
        final ApplicableRegionSet set = getApplicableRegions(position);
        return option == RegionQuery.QueryOption.COMPUTE_PARENTS ? RegionQuery.uwgWithParents(set) : set;
    }

    /**
     * @see #getApplicableRegions(ProtectedRegion)
     */
    public ApplicableRegionSet getApplicableRegions(final ProtectedRegion region,
                                                    final RegionQuery.QueryOption option) {
        final ApplicableRegionSet set = getApplicableRegions(region);
        return option == RegionQuery.QueryOption.COMPUTE_PARENTS ? RegionQuery.uwgWithParents(set) : set;
    }

    /**
     * How many regions {@code player} owns in this world. Ownership inherited from a parent region
     * counts, as it does everywhere else in the engine.
     */
    public int getRegionCountOfPlayer(final com.sk89q.worldguard.LocalPlayer player) {
        final java.util.UUID uniqueId = player.getUniqueId();
        int count = 0;
        for (final com.tricrotism.uworldguard.region.ProtectedRegion region : backing.getRegions()) {
            if (region.isOwner(uniqueId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether {@code region} overlaps any region {@code player} does not own — the claim check.
     * Bounding-box based, like every other overlap test in this layer.
     */
    public boolean overlapsUnownedRegion(
        final ProtectedRegion region, final com.sk89q.worldguard.LocalPlayer player
    ) {
        final java.util.UUID uniqueId = player.getUniqueId();
        for (final com.tricrotism.uworldguard.region.ProtectedRegion candidate : backing.getRegions()) {
            if (candidate.isOwner(uniqueId)) {
                continue;
            }
            final ProtectedRegion shim =
                com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(candidate, backing);
            if (shim != region && overlaps(shim, region)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getApplicableRegionsIDs(final BlockVector3 position) {
        final com.tricrotism.uworldguard.region.ApplicableRegionSet set =
            backing.getApplicableRegions(position.x(), position.y(), position.z());
        final List<String> ids = new ArrayList<>(set.size());
        for (int i = 0, n = set.size(); i < n; i++) {
            ids.add(set.get(i).getId());
        }
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_READS.increment();
        return ids;
    }

    /**
     * @deprecated exact id first, then the first region whose id starts with {@code pattern}.
     */
    @Deprecated
    public ProtectedRegion matchRegion(final String pattern) {
        final ProtectedRegion exact = getRegion(pattern);
        if (exact != null) {
            return exact;
        }
        final String prefix = pattern.toLowerCase(Locale.ROOT);
        for (final com.tricrotism.uworldguard.region.ProtectedRegion region : backing.getRegions()) {
            if (region.getId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(region, backing);
            }
        }
        return null;
    }

    public void addRegion(final ProtectedRegion region) {
        if (region instanceof com.sk89q.worldguard.protection.regions.GlobalProtectedRegion global
            && global.uwgIsTemplate()) {
            java.util.logging.Logger.getLogger("uWorldGuard").warning("A plugin added a global region under"
                + " its own id as a template. uWorldGuard has one global region per world, so it was"
                + " not added, since adding it would have replaced __global__ and its flags.");
            return;
        }
        region.uwgAttach(backing);
        adoptParent(region.uwgBacking());
        backing.addRegion(region.uwgBacking());
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_MUTATIONS.increment();
    }

    /**
     * A region copied from another world keeps that world's parent, which flag resolution here can't
     * see. Point it at this world's region of the same id instead, or drop the parent if there is none.
     */
    private void adoptParent(final com.tricrotism.uworldguard.region.ProtectedRegion region) {
        final com.tricrotism.uworldguard.region.ProtectedRegion parent = region.getParent();
        if (parent == null) {
            return;
        }
        final com.tricrotism.uworldguard.region.RegionManager parentWorld = parent.uwgOwner();
        if (parentWorld == null || parentWorld == backing) {
            return;
        }
        final com.tricrotism.uworldguard.region.ProtectedRegion local = backing.getRegion(parent.getId());
        try {
            region.setParent(local);
        } catch (final IllegalArgumentException circular) {
            region.setParent(null);
        }
    }

    public Set<ProtectedRegion> removeRegion(final String id) {
        return removeRegion(id, RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
    }

    public Set<ProtectedRegion> removeRegion(final String id, final RemovalStrategy strategy) {
        final com.tricrotism.uworldguard.region.ProtectedRegion target = backing.getRegion(id);
        if (target == null) {
            return null;
        }

        final List<com.tricrotism.uworldguard.region.ProtectedRegion> doomed = new ArrayList<>(1);
        doomed.add(target);
        if (strategy == RemovalStrategy.REMOVE_CHILDREN) {
            collectDescendants(target, doomed);
        }

        final Set<ProtectedRegion> removed = new LinkedHashSet<>(doomed.size());
        for (int i = 0, n = doomed.size(); i < n; i++) {
            final com.tricrotism.uworldguard.region.ProtectedRegion region = doomed.get(i);
            removed.add(com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(region, backing));
            backing.removeRegion(region.getId());
        }
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_MUTATIONS.increment();
        return removed;
    }

    public void setRegions(final Collection<ProtectedRegion> regions) {
        clearRegions();
        for (final ProtectedRegion region : regions) {
            addRegion(region);
        }
    }

    public void setRegions(final Map<String, ProtectedRegion> regions) {
        setRegions(regions.values());
    }

    /**
     * Marks this world's regions for save. uWorldGuard writes them out on its own schedule, so this
     * never blocks.
     *
     * <p>A flag value edited in place, such as a set a consumer added to, never reached the engine,
     * and this mark is what saves it. Shared region sets hold that same value object, so they stay
     * valid and are not retired.
     */
    public void save() throws StorageException {
        backing.uwgMarkUnsaved();
    }

    /**
     * @see #save()
     */
    public boolean saveChanges() throws StorageException {
        backing.uwgMarkUnsaved();
        return true;
    }

    /**
     * No-op: uWorldGuard owns region loading.
     */
    public void load() throws StorageException {
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.stub("RegionManager.load");
    }

    /**
     * No-op: uWorldGuard keeps every region of a loaded world in memory.
     */
    public void loadChunk(final BlockVector2 position) {
    }

    /**
     * @see #loadChunk(BlockVector2)
     */
    public void loadChunks(final Collection<BlockVector2> positions) {
    }

    /**
     * @see #loadChunk(BlockVector2)
     */
    public void unloadChunk(final BlockVector2 position) {
    }

    private void clearRegions() {
        for (final com.tricrotism.uworldguard.region.ProtectedRegion region :
            new ArrayList<>(backing.getRegions())) {
            backing.removeRegion(region.getId());
        }
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_MUTATIONS.increment();
    }

    private void collectDescendants(
        final com.tricrotism.uworldguard.region.ProtectedRegion parent, final List<com.tricrotism.uworldguard.region.ProtectedRegion> out
    ) {
        for (final com.tricrotism.uworldguard.region.ProtectedRegion candidate : backing.getRegions()) {
            if (candidate.getParent() == parent && !out.contains(candidate)) {
                out.add(candidate);
                collectDescendants(candidate, out);
            }
        }
    }

    private ProtectedRegion globalRegion() {
        final com.tricrotism.uworldguard.region.ProtectedRegion global =
            backing.getRegion(com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID);
        return global == null ? null
            : com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(global, backing);
    }

    private static boolean overlaps(final ProtectedRegion a, final ProtectedRegion b) {
        final BlockVector3 aMin = a.getMinimumPoint();
        final BlockVector3 aMax = a.getMaximumPoint();
        final BlockVector3 bMin = b.getMinimumPoint();
        final BlockVector3 bMax = b.getMaximumPoint();
        return aMax.x() >= bMin.x() && aMin.x() <= bMax.x()
            && aMax.y() >= bMin.y() && aMin.y() <= bMax.y()
            && aMax.z() >= bMin.z() && aMin.z() <= bMax.z();
    }
}
