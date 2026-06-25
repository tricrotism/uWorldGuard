package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Holds all regions for a single world. Thread-safe; queried from region threads and
 * edited from command threads.
 *
 * <p>Applicable-region lookup is backed by a per-chunk candidate cache. The first query in a
 * chunk scans every region for those whose bounding box overlaps it and caches that (usually
 * tiny, often empty) list; later queries in the chunk test only those candidates. Wilderness
 * chunks cache a shared empty list, so the common no-region case is a single array-slot read. The
 * cache is dropped wholesale when a region is added or removed — bounds are immutable and
 * flag/priority/parent edits read through to the live region, so nothing else changes what
 * overlaps a chunk — and is a fixed-capacity direct-mapped table (primitive {@code long} keys, no
 * boxing) to bound memory. A spatial index (R-tree) remains the endgame for worlds with very many
 * large, overlapping regions.
 */
@NullMarked
public final class RegionManager {

    private final Map<String, ProtectedRegion> regions = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile @Nullable GlobalProtectedRegion global;
    private volatile @Nullable ApplicableRegionSet emptySet;

    private volatile boolean flagIndexStale = true;
    private volatile Set<Flag<?>> usedFlags = Set.of();

    private static final int CHUNK_CACHE_BITS = 14;
    private static final int CHUNK_CACHE_SLOTS = 1 << CHUNK_CACHE_BITS; // 16384
    private static final long CHUNK_HASH_MULTIPLIER = 0x9E3779B97F4A7C15L; // fibonacci hashing
    private volatile AtomicReferenceArray<@Nullable ChunkCandidates> chunkIndex = new AtomicReferenceArray<>(CHUNK_CACHE_SLOTS);

    public void addRegion(final ProtectedRegion region) {
        if (region instanceof GlobalProtectedRegion g) {
            global = g;
        }
        regions.put(region.getId().toLowerCase(Locale.ROOT), region);
        dirty.set(true);
        flagIndexStale = true;
        invalidateChunkIndex();
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
            invalidateChunkIndex();
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
        return getApplicableRegions(point.x(), point.y(), point.z());
    }

    public ApplicableRegionSet getApplicableRegions(final int x, final int y, final int z) {
        final long key = chunkKey(x, z);
        final AtomicReferenceArray<@Nullable ChunkCandidates> cache = chunkIndex;
        final int slot = (int) ((key * CHUNK_HASH_MULTIPLIER) >>> (64 - CHUNK_CACHE_BITS));
        final ChunkCandidates cached = cache.get(slot);
        final List<ProtectedRegion> candidates;
        if (cached != null && cached.key() == key) {
            candidates = cached.regions();
        } else {
            candidates = buildChunkCandidates(key);
            cache.set(slot, new ChunkCandidates(key, candidates));
        }
        if (candidates.isEmpty()) {
            return emptySet();
        }
        List<ProtectedRegion> matches = null;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            final ProtectedRegion region = candidates.get(i);
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
     * Regions whose XZ bounding box overlaps the given chunk — the candidate set every query in
     * that chunk is narrowed to. Built once per chunk (the only full scan), then cached. The Y
     * bound and exact {@link ProtectedRegion#contains} are still tested per query, so non-cuboid
     * shapes resolve correctly. Global regions are excluded; they are not spatial.
     */
    private List<ProtectedRegion> buildChunkCandidates(final long key) {
        final int minBx = ((int) (key >> 32)) << 4;
        final int maxBx = minBx + 15;
        final int minBz = ((int) key) << 4;
        final int maxBz = minBz + 15;
        List<ProtectedRegion> list = null;
        for (final ProtectedRegion region : regions.values()) {
            if (region instanceof GlobalProtectedRegion) {
                continue;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            if (max.x() < minBx || min.x() > maxBx || max.z() < minBz || min.z() > maxBz) {
                continue;
            }
            if (list == null) {
                list = new ArrayList<>(4);
            }
            list.add(region);
        }
        return list == null ? List.of() : list;
    }

    private static long chunkKey(final int x, final int z) {
        return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    }

    /**
     * A published cache slot: the chunk key it answers for, and the regions whose XZ bounds overlap
     * that chunk. The region list is a fresh snapshot never mutated after construction, so the record
     * is safe to read without locking once stored via {@link AtomicReferenceArray#set} (a volatile
     * write that publishes it).
     */
    private record ChunkCandidates(long key, List<ProtectedRegion> regions) {}

    /**
     * Drop the per-chunk cache on a region add/remove (which can change what overlaps a chunk).
     * Assigns a fresh table rather than clearing in place, so a query mid-build can never publish a
     * stale candidate list — its store lands in the now-orphaned old table. The table is a
     * fixed-capacity direct-mapped cache: a fresh chunk hashing to an occupied slot simply overwrites
     * it, bounding memory without per-lookup boxing or eviction bookkeeping.
     */
    private void invalidateChunkIndex() {
        chunkIndex = new AtomicReferenceArray<>(CHUNK_CACHE_SLOTS);
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
