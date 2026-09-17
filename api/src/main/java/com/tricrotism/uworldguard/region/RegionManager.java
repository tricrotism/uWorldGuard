package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
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
    private volatile @Nullable Object compatShim;
    /**
     * Its own monitor rather than the manager's: {@link #rebuildFlagIndex} holds that one across a
     * walk of every region, and a world's first compat query has no reason to wait behind it.
     */
    private final Object compatShimLock = new Object();

    private volatile boolean flagIndexStale = true;
    private volatile long[] usedFlagBits = EMPTY_BITS;
    private volatile boolean groupsInUse;

    private static final long[] EMPTY_BITS = new long[0];

    private static final int CHUNK_CACHE_BITS = 14;
    private static final int CHUNK_CACHE_SLOTS = 1 << CHUNK_CACHE_BITS; // 16384
    private static final long CHUNK_HASH_MULTIPLIER = 0x9E3779B97F4A7C15L; // fibonacci hashing
    private volatile AtomicReferenceArray<@Nullable ChunkCandidates> chunkIndex = new AtomicReferenceArray<>(CHUNK_CACHE_SLOTS);

    /**
     * Add {@code region}, replacing whatever held its id.
     *
     * <p>A replacement takes over the old region's place in the tree the same way
     * {@link #redefineRegion} does. Without that, every child kept pointing at the instance that just
     * left the map: it is unreachable, never saved, and still supplying the flags its children
     * inherit, so an import with {@code --overwrite} left the old values in force until a restart.
     */
    public void addRegion(final ProtectedRegion region) {
        final ProtectedRegion replaced = regions.put(region.getId().toLowerCase(Locale.ROOT), region);
        if (region instanceof GlobalProtectedRegion g) {
            global = g;
        } else if (replaced != null && replaced == global) {
            global = null;
        }
        if (replaced != null && replaced != region) {
            for (final ProtectedRegion r : regions.values()) {
                if (r != region && r.getParent() == replaced) {
                    r.setParent(region);
                }
            }
            if (region.getParent() == replaced) {
                region.setParent(null);
            }
        }
        dirty.set(true);
        flagIndexStale = true;
        invalidateChunkIndex();
    }

    /**
     * Add {@code region} only if its id is free, returning the region already holding that id — or
     * {@code null} on success. For the {@code define} commands, where checking with {@link #hasRegion}
     * and then calling {@link #addRegion} leaves a window: two admins defining the same name from
     * different region threads both pass the check, and the second put silently replaces the first
     * region's bounds, owners and flags while telling both of them it was created.
     */
    public @Nullable ProtectedRegion addRegionIfAbsent(final ProtectedRegion region) {
        final ProtectedRegion existing =
            regions.putIfAbsent(region.getId().toLowerCase(Locale.ROOT), region);
        if (existing != null) {
            return existing;
        }
        if (region instanceof GlobalProtectedRegion g) {
            global = g;
        }
        dirty.set(true);
        flagIndexStale = true;
        invalidateChunkIndex();
        return null;
    }

    /**
     * Replace the region holding {@code replacement}'s id with {@code replacement}, which inherits
     * the old region's flags, owners, members, priority and parent, and takes over as the parent of
     * any region that pointed at the old one. Returns the region that was replaced, or {@code null}
     * if the id was not in use.
     *
     * <p>Backs {@code /uwg redefine}. One swap rather than remove-then-add: bounds are immutable, so
     * reshaping means a new instance, and a query landing in the gap between a remove and an add
     * would see the area as unprotected. The state copy runs inside the map's compute, so a second
     * redefine of the same id cannot interleave with it.
     */
    public @Nullable ProtectedRegion redefineRegion(final ProtectedRegion replacement) {
        final ProtectedRegion[] previous = new ProtectedRegion[1];
        regions.computeIfPresent(
            replacement.getId().toLowerCase(Locale.ROOT),
            (key, existing) -> {
                previous[0] = existing;
                replacement.copyStateFrom(existing);
                return replacement;
            });

        final ProtectedRegion replaced = previous[0];
        if (replaced == null) {
            return null;
        }

        if (replacement instanceof GlobalProtectedRegion g) {
            global = g;
        } else if (replaced == global) {
            global = null;
        }
        for (final ProtectedRegion r : regions.values()) {
            if (r.getParent() == replaced) {
                r.setParent(replacement);
            }
        }
        dirty.set(true);
        flagIndexStale = true;
        invalidateChunkIndex();
        return replaced;
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
     * Regions whose bounding box overlaps the box from {@code a} to {@code b} (inclusive, either
     * corner first), highest priority first. The global region is never included.
     *
     * <p>This is the check a claim needs before it is created: does the new area touch anything that
     * exists? It compares bounding boxes, so for a cylinder, sphere or polygon a hit means the two
     * may overlap. Test {@link ProtectedRegion#contains} on the blocks that matter when that
     * difference counts.
     *
     * <p>Walks every region in the world, so run it when a claim is made, not per move.
     */
    public List<ProtectedRegion> getRegionsIntersecting(final BlockVector3 a, final BlockVector3 b) {
        final int minX = Math.min(a.x(), b.x());
        final int minY = Math.min(a.y(), b.y());
        final int minZ = Math.min(a.z(), b.z());
        final int maxX = Math.max(a.x(), b.x());
        final int maxY = Math.max(a.y(), b.y());
        final int maxZ = Math.max(a.z(), b.z());
        final List<ProtectedRegion> hits = new ArrayList<>();
        for (final ProtectedRegion region : regions.values()) {
            if (region instanceof GlobalProtectedRegion) {
                continue;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            if (max.x() >= minX && min.x() <= maxX
                && max.y() >= minY && min.y() <= maxY
                && max.z() >= minZ && min.z() <= maxZ) {
                hits.add(region);
            }
        }
        hits.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed());
        return hits;
    }

    /**
     * Internal (compat layer): the WorldGuard-API shim built over this manager, or {@code null} while
     * nothing has asked for one. Plugins should not call this.
     *
     * @see ProtectedRegion#uwgCompatShim()
     */
    public @Nullable Object uwgCompatShim() {
        return compatShim;
    }

    /**
     * Internal (compat layer): publish {@code shim} as this manager's wrapper, returning whichever
     * instance won when two threads wrapped the same manager at once. Plugins should not call this.
     */
    public Object uwgLinkCompatShim(final Object shim) {
        synchronized (compatShimLock) {
            final Object existing = compatShim;
            if (existing != null) {
                return existing;
            }
            compatShim = shim;
            return shim;
        }
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
     * Whether any region in this world sets {@code flag} directly. Backed by a bitset over
     * {@link Flag#getIndex()} rebuilt lazily after a mutation, so a check is a volatile read plus one
     * word test — cheap enough to gate per-move flag reads, not just the once-a-second services.
     * Inherited flags count because the parent that defines them is itself a region.
     */
    public boolean anyRegionUses(final Flag<?> flag) {
        if (flagIndexStale) {
            rebuildFlagIndex();
        }
        final int index = flag.getIndex();
        if (index < 0) {
            return false;
        }
        final long[] bits = usedFlagBits;
        final int word = index >> 6;
        return word < bits.length && (bits[word] & (1L << index)) != 0L;
    }

    private synchronized void rebuildFlagIndex() {
        if (!flagIndexStale) {
            return;
        }
        flagIndexStale = false;
        final long[] bits = new long[(Flags.count() >> 6) + 1];
        boolean any = false;
        boolean groups = false;
        for (final ProtectedRegion region : regions.values()) {
            if (!region.getFlagGroups().isEmpty()) {
                groups = true;
            }
            for (final Flag<?> flag : region.getFlags().keySet()) {
                final int index = flag.getIndex();
                if (index >= 0 && (index >> 6) < bits.length) {
                    bits[index >> 6] |= 1L << index;
                    any = true;
                }
            }
        }
        usedFlagBits = any ? bits : EMPTY_BITS;
        groupsInUse = groups;
    }

    /**
     * Whether any region in this world carries a group qualifier. Almost no server uses them, so this
     * lets flag resolution skip the per-region association check entirely rather than paying two map
     * lookups per region per flag on the hottest path in the plugin.
     */
    boolean anyFlagGroups() {
        if (flagIndexStale) {
            rebuildFlagIndex();
        }
        return groupsInUse;
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
        return new ApplicableRegionSet(matches, global, this);
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
     * reused instead of allocated per event.
     *
     * <p>Rebuilt when the global region changes <em>or</em> when the world's group-qualifier state
     * flips. A set snapshots {@code groupsInUse} at construction, and the empty set is the one place
     * that snapshot can outlive the fact: with no applicable regions the only value left to resolve
     * is the global region's, so a qualifier added to it afterwards would be ignored in wilderness
     * for as long as the cached set survived.
     */
    private ApplicableRegionSet emptySet() {
        final GlobalProtectedRegion g = global;
        final boolean groups = anyFlagGroups();
        ApplicableRegionSet cached = emptySet;
        if (cached == null || cached.globalRegion() != g || cached.usesGroups() != groups) {
            cached = new ApplicableRegionSet(List.of(), g, this);
            emptySet = cached;
        }
        return cached;
    }
}
