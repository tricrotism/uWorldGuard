package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds all regions for a single world. Thread-safe; queried from region threads and
 * edited from command threads.
 *
 * <p>Applicable-region lookup is a linear scan with a cheap bounding-box reject. That is
 * fine for typical region counts; a spatial index (R-tree) is a planned performance phase
 * for worlds with thousands of regions.
 */
@NullMarked
public final class RegionManager {

    private final Map<String, ProtectedRegion> regions = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile @Nullable GlobalProtectedRegion global;
    private volatile @Nullable ApplicableRegionSet emptySet;

    private volatile boolean flagIndexStale = true;
    private volatile Set<Flag<?>> usedFlags = Set.of();

    public void addRegion(final ProtectedRegion region) {
        if (region instanceof GlobalProtectedRegion g) {
            global = g;
        }
        regions.put(region.getId().toLowerCase(Locale.ROOT), region);
        dirty.set(true);
        flagIndexStale = true;
    }

    public @Nullable ProtectedRegion removeRegion(final String id) {
        final ProtectedRegion removed = regions.remove(id.toLowerCase(Locale.ROOT));
        if (removed != null) {
            if (removed == global) {
                global = null;
            }

            for (final ProtectedRegion r : regions.values()) {
                if (r.getParent() == removed) {
                    r.setParent(null);
                }
            }
            dirty.set(true);
            flagIndexStale = true;
        }
        return removed;
    }

    public @Nullable ProtectedRegion getRegion(final String id) {
        return regions.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hasRegion(final String id) {
        return regions.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public Collection<ProtectedRegion> getRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public int size() {
        return regions.size();
    }

    /**
     * Internal (persistence): consume the dirty bit. Plugins should not call this.
     */
    public boolean clearDirty() {
        return dirty.getAndSet(false);
    }

    /**
     * Mark this world's regions as needing a save — call after mutating a region's
     * flags, domains, priority, or parent directly.
     */
    public void markDirty() {
        dirty.set(true);
        flagIndexStale = true;
    }

    /**
     * Whether any region in this world sets {@code flag} directly. Backed by a cached index
     * rebuilt lazily after a mutation, so repeated polling (e.g. the heal task) is a single
     * set lookup. Inherited flags count because the parent that defines them is itself a region.
     */
    public boolean anyRegionUses(final Flag<?> flag) {
        if (flagIndexStale) {
            rebuildFlagIndex();
        }
        return usedFlags.contains(flag);
    }

    private synchronized void rebuildFlagIndex() {
        if (!flagIndexStale) {
            return;
        }
        flagIndexStale = false;
        final Set<Flag<?>> used = new HashSet<>();
        for (final ProtectedRegion region : regions.values()) {
            used.addAll(region.getFlags().keySet());
        }
        usedFlags = used.isEmpty() ? Set.of() : Set.copyOf(used);
    }

    public ApplicableRegionSet getApplicableRegions(final BlockVector3 point) {
        List<ProtectedRegion> matches = null;
        final int x = point.x();
        final int y = point.y();
        final int z = point.z();
        for (final ProtectedRegion region : regions.values()) {
            if (region instanceof GlobalProtectedRegion) {
                continue;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            if (x < min.x() || x > max.x() || y < min.y() || y > max.y() || z < min.z() || z > max.z()) {
                continue;
            }
            if (region.contains(x, y, z)) {
                if (matches == null) {
                    matches = new ArrayList<>(4);
                }
                matches.add(region);
            }
        }
        if (matches == null) {
            return emptySet();
        }
        return new ApplicableRegionSet(matches, global);
    }

    /**
     * Cached no-match result. Most queries hit unprotected wilderness, so the empty set is
     * reused instead of allocated per event; it is rebuilt only when the global region changes.
     */
    private ApplicableRegionSet emptySet() {
        final GlobalProtectedRegion g = global;
        ApplicableRegionSet cached = emptySet;
        if (cached == null || cached.globalRegion() != g) {
            cached = new ApplicableRegionSet(List.of(), g);
            emptySet = cached;
        }
        return cached;
    }
}
