package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Holds all regions for a single world. Thread-safe: queried from region threads and
 * edited from command threads.
 *
 * <p>Applicable-region lookup is backed by a per-chunk candidate cache. The first query in a
 * chunk finds the regions whose bounding box overlaps it and caches that (usually tiny, often
 * empty) list, and later queries in the chunk test only those candidates. Wilderness chunks cache
 * a shared empty list, so the common no-region case is a single array-slot read. A region added or
 * removed retires only the cached chunks its box reaches, checked when a chunk is next read. Bounds
 * are immutable and flag, priority and parent edits read through to the live region, so nothing
 * else changes what overlaps a chunk. The cache is a fixed-capacity table (primitive {@code long}
 * keys, no boxing) to bound memory. A spatial index (R-tree) remains the endgame for worlds with
 * very many large, overlapping regions.
 */
@NullMarked
public final class RegionManager {

    private final Map<String, ProtectedRegion> regions = new ConcurrentHashMap<>();
    /**
     * Regions whose id is not all lower case, by that exact id. Storage backends hand ids back as
     * written, and without this a mixed-case id was lowercased into a new string on every lookup. An
     * entry counts only while its region is still owned by this manager, so a stale one costs the
     * lowercase fallback, never a wrong answer.
     */
    private final Map<String, ProtectedRegion> mixedCaseIds = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile @Nullable GlobalProtectedRegion global;
    private volatile @Nullable ApplicableRegionSet emptySet;
    private volatile @Nullable Object compatShim;
    /**
     * Its own monitor rather than the manager's: {@link #rebuildFlagIndex} holds that one across a
     * walk of every region, and a world's first compat query has no reason to wait behind it.
     */
    private final Object compatShimLock = new Object();

    /**
     * Bumped after every edit that can change which flags are set. The index is current while
     * {@link #flagIndexBuiltAt} matches it. A rebuild records the count it started from, so an edit
     * landing mid-walk leaves the two apart and the next read rebuilds again.
     *
     * <p>A field rather than an {@code AtomicLong}, since every flag check and shared set reads it and
     * the extra object would be one more dependent load each time.
     */
    private volatile long flagEdits;
    private static final AtomicLongFieldUpdater<RegionManager> FLAG_EDITS =
        AtomicLongFieldUpdater.newUpdater(RegionManager.class, "flagEdits");
    private volatile long flagIndexBuiltAt = -1L;
    private volatile long[] usedFlagBits = EMPTY_BITS;
    private volatile boolean groupsInUse;
    /**
     * How many regions set each flag, by flag index, and carry each group qualifier. Guarded by this.
     */
    private int[] flagUses = new int[0];
    private int groupUses;

    private static final long[] EMPTY_BITS = new long[0];
    private static final Comparator<ProtectedRegion> PRIORITY_DESC =
        Comparator.comparingInt(ProtectedRegion::getPriority).reversed();

    private static final int CHUNK_CACHE_BITS = 14;
    private static final int CHUNK_CACHE_SLOTS = 1 << CHUNK_CACHE_BITS; // 16384
    private static final long CHUNK_HASH_MULTIPLIER = 0x9E3779B97F4A7C15L; // fibonacci hashing
    /**
     * {@code null} until the first query installs the table, which then lasts as long as the manager.
     * Edits retire entries in it, not the table. See {@link #geometryChanged}.
     */
    private volatile @Nullable ChunkTable chunkIndex;
    private static final AtomicReferenceFieldUpdater<RegionManager, ChunkTable> CHUNK_INDEX =
        AtomicReferenceFieldUpdater.newUpdater(RegionManager.class, ChunkTable.class, "chunkIndex");
    private volatile @Nullable SpatialIndex spatialIndex;

    /**
     * Geometry edits so far. Each is logged in {@link #recentEdits} before the count is published, and
     * a cached chunk records the count it was last known good at, so a read can tell whether any edit
     * since then reaches it.
     */
    private volatile long geometryEdits;
    private static final int RECENT_EDITS = 64;
    private final AtomicReferenceArray<@Nullable GeometryEdit> recentEdits = new AtomicReferenceArray<>(RECENT_EDITS);
    /**
     * Built by the first chunk fill that needs it and then kept in step with every add and remove.
     */
    private volatile @Nullable RegionGrid grid;
    /**
     * Serializes grid edits and the grid's first build, so neither can lose a region to the other.
     */
    private final Object gridLock = new Object();

    /**
     * Internal (storage backends): fill the chunk cache and answer box queries from {@code index}, or
     * go back to scanning the manager's own regions with {@code null}. Install it once the manager
     * holds what the index already knows, since only later edits are forwarded. Plugins should not call this.
     */
    public void uwgUseIndex(final @Nullable SpatialIndex index) {
        spatialIndex = index;
        synchronized (gridLock) {
            grid = null;
            logEdit(EVERYWHERE);
        }
    }

    private void indexPut(final ProtectedRegion region) {
        final SpatialIndex index = spatialIndex;
        if (index != null) {
            index.put(region);
        }
    }

    /**
     * Add {@code region}, replacing whatever held its id.
     *
     * <p>A replacement takes over the old region's place in the tree the same way
     * {@link #redefineRegion} does. Without that, every child kept pointing at the instance that just
     * left the map: it is unreachable, never saved, and still supplying the flags its children
     * inherit, so an import with {@code --overwrite} left the old values in force until a restart.
     * The replaced instance is then detached from this world (see {@code retire}).
     *
     * <p>Adding the instance already held only marks the world for saving and hands it to the storage
     * index again, which is how an owner or member edit reaches the index. Its bounds are fixed and its
     * flags were counted as they were set, so the flag index, shared sets and chunk cache stand.
     */
    public void addRegion(final ProtectedRegion region) {
        final ProtectedRegion replaced;
        synchronized (this) {
            region.uwgOwnedBy(this);
            final String id = region.getId();
            final String key = id.toLowerCase(Locale.ROOT);
            replaced = regions.put(key, region);
            trackCase(id, key, region);
            if (region instanceof GlobalProtectedRegion g) {
                global = g;
            } else if (replaced != null && replaced == global) {
                global = null;
            }
            if (replaced != null && replaced != region) {
                if (replaced.uwgHasChildren()) {
                    for (final ProtectedRegion r : regions.values()) {
                        if (r != region && r.getParent() == replaced) {
                            r.setParent(region);
                        }
                    }
                }
                if (region.getParent() == replaced) {
                    region.setParent(null);
                }
                retire(replaced);
            }
            dirty.set(true);
            if (replaced != region) {
                recount(replaced, region);
            }
        }
        indexPut(region);
        if (replaced != region) {
            geometryChanged(replaced, region);
        }
    }

    /**
     * Detach a region that just left the map. Callers of the WorldGuard API keep region objects, and
     * an edit through a stale one used to reach this world: it re-sent the old geometry and flags to
     * the storage index. Its parent link goes too, or the parent kept counting it as a child forever.
     */
    private void retire(final ProtectedRegion gone) {
        gone.uwgOwnedBy(null);
        gone.setParent(null);
        mixedCaseIds.remove(gone.getId(), gone);
    }

    private void trackCase(final String id, final String key, final ProtectedRegion region) {
        if (!key.equals(id)) {
            mixedCaseIds.put(id, region);
        }
    }

    /**
     * Add {@code region} only if its id is free, returning the region already holding that id — or
     * {@code null} on success. For the {@code define} commands, where checking with {@link #hasRegion}
     * and then calling {@link #addRegion} leaves a window: two admins defining the same name from
     * different region threads both pass the check, and the second put silently replaces the first
     * region's bounds, owners and flags while telling both of them it was created.
     */
    public @Nullable ProtectedRegion addRegionIfAbsent(final ProtectedRegion region) {
        synchronized (this) {
            final String id = region.getId();
            final String key = id.toLowerCase(Locale.ROOT);
            final ProtectedRegion existing = regions.putIfAbsent(key, region);
            if (existing != null) {
                return existing;
            }
            region.uwgOwnedBy(this);
            trackCase(id, key, region);
            if (region instanceof GlobalProtectedRegion g) {
                global = g;
            }
            dirty.set(true);
            recount(null, region);
        }
        indexPut(region);
        geometryChanged(null, region);
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
        final ProtectedRegion replaced;
        synchronized (this) {
            final String id = replacement.getId();
            final String lower = id.toLowerCase(Locale.ROOT);
            final ProtectedRegion[] previous = new ProtectedRegion[1];
            regions.computeIfPresent(
                lower,
                (key, existing) -> {
                    previous[0] = existing;
                    replacement.copyStateFrom(existing);
                    return replacement;
                });

            replaced = previous[0];
            if (replaced == null) {
                return null;
            }
            replacement.uwgOwnedBy(this);
            trackCase(id, lower, replacement);

            if (replacement instanceof GlobalProtectedRegion g) {
                global = g;
            } else if (replaced == global) {
                global = null;
            }
            if (replaced.uwgHasChildren()) {
                for (final ProtectedRegion r : regions.values()) {
                    if (r.getParent() == replaced) {
                        r.setParent(replacement);
                    }
                }
            }
            retire(replaced);
            dirty.set(true);
            recount(replaced, replacement);
        }
        indexPut(replacement);
        geometryChanged(replaced, replacement);
        return replaced;
    }

    public @Nullable ProtectedRegion removeRegion(final String id) {
        final ProtectedRegion removed;
        synchronized (this) {
            removed = regions.remove(id.toLowerCase(Locale.ROOT));
            if (removed == null) {
                return null;
            }
            removed.uwgOwnedBy(null);
            mixedCaseIds.remove(removed.getId(), removed);
            if (removed == global) {
                global = null;
            }

            if (removed.uwgHasChildren()) {
                for (final ProtectedRegion r : regions.values()) {
                    if (r.getParent() == removed) {
                        r.setParent(null);
                    }
                }
            }
            dirty.set(true);
            recount(removed, null);
        }
        final SpatialIndex index = spatialIndex;
        if (index != null) {
            index.remove(removed);
        }
        geometryChanged(removed, null);
        return removed;
    }

    /**
     * Ids are keyed in lower case. An id already in lower case hits on the first lookup, and a
     * mixed-case id as the region spells it hits {@code mixedCaseIds}, so neither allocates. Only an
     * id in some other case pays for lowercasing.
     */
    public @Nullable ProtectedRegion getRegion(final String id) {
        final ProtectedRegion exact = regions.get(id);
        if (exact != null) {
            return exact;
        }
        final ProtectedRegion spelled = mixedCaseIds.get(id);
        if (spelled != null && spelled.uwgOwner() == this) {
            return spelled;
        }
        return regions.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hasRegion(final String id) {
        return getRegion(id) != null;
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
     * <p>Without a backend index a box wider than four chunks walks every region in the world, so
     * run it when a claim is made, not per move.
     */
    public List<ProtectedRegion> getRegionsIntersecting(final BlockVector3 a, final BlockVector3 b) {
        final int minX = Math.min(a.x(), b.x());
        final int minY = Math.min(a.y(), b.y());
        final int minZ = Math.min(a.z(), b.z());
        final int maxX = Math.max(a.x(), b.x());
        final int maxY = Math.max(a.y(), b.y());
        final int maxZ = Math.max(a.z(), b.z());
        final SpatialIndex index = spatialIndex;
        if (index != null && (long) ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1) > 4) {
            final List<ProtectedRegion> hits = new ArrayList<>(index.intersecting(minX, minY, minZ, maxX, maxY, maxZ));
            hits.sort(PRIORITY_DESC);
            return hits;
        }
        return intersectingWithoutIndex(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Kept apart from the indexed path above so that path stays small enough for C2 to inline.
     */
    private List<ProtectedRegion> intersectingWithoutIndex(
        final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ
    ) {
        final int minCx = minX >> 4;
        final int maxCx = maxX >> 4;
        final int minCz = minZ >> 4;
        final int maxCz = maxZ >> 4;
        final List<ProtectedRegion> hits = new ArrayList<>();
        if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) <= 4) {
            final boolean several = minCx != maxCx || minCz != maxCz;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    final List<ProtectedRegion> candidates = chunkCandidates(((long) cx << 32) | (cz & 0xFFFFFFFFL)).regions();
                    for (int i = 0, n = candidates.size(); i < n; i++) {
                        final ProtectedRegion region = candidates.get(i);
                        final BlockVector3 min = region.getMinimumPoint();
                        final BlockVector3 max = region.getMaximumPoint();
                        if (max.x() >= minX && min.x() <= maxX
                            && max.y() >= minY && min.y() <= maxY
                            && max.z() >= minZ && min.z() <= maxZ
                            && (!several || !containsSame(hits, region))) {
                            hits.add(region);
                        }
                    }
                }
            }
            hits.sort(PRIORITY_DESC);
            return hits;
        }
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
        hits.sort(PRIORITY_DESC);
        return hits;
    }

    /**
     * For a blast: the regions touching {@code box} ({@code minX, minY, minZ, maxX, maxY, maxZ}) that
     * do not hold all of it, in no order, or {@code null} when every region touching it holds all of
     * it. A box over at most {@link #BLAST_CHUNKS} chunks reads the chunk cache, so a blast inside its
     * regions builds and sorts no list.
     */
    private static final int BLAST_CHUNKS = 16;

    ProtectedRegion @Nullable [] crossing(final int[] box) {
        final int minCx = box[0] >> 4;
        final int maxCx = box[3] >> 4;
        final int minCz = box[2] >> 4;
        final int maxCz = box[5] >> 4;
        ProtectedRegion[] out = null;
        int c = 0;
        if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) <= BLAST_CHUNKS) {
            final boolean several = minCx != maxCx || minCz != maxCz;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    final List<ProtectedRegion> candidates = chunkCandidates(((long) cx << 32) | (cz & 0xFFFFFFFFL)).regions();
                    for (int i = 0, n = candidates.size(); i < n; i++) {
                        final ProtectedRegion region = candidates.get(i);
                        if (crosses(region, box) && (!several || out == null || !containsSame(out, c, region))) {
                            if (out == null) {
                                out = new ProtectedRegion[4];
                            } else if (c == out.length) {
                                out = Arrays.copyOf(out, 2 * c);
                            }
                            out[c++] = region;
                        }
                    }
                }
            }
        } else {
            final List<ProtectedRegion> hits = getRegionsIntersecting(
                BlockVector3.at(box[0], box[1], box[2]), BlockVector3.at(box[3], box[4], box[5]));
            for (int i = 0, n = hits.size(); i < n; i++) {
                final ProtectedRegion region = hits.get(i);
                if (crosses(region, box)) {
                    if (out == null) {
                        out = new ProtectedRegion[n - i];
                    }
                    out[c++] = region;
                }
            }
        }
        return out == null || c == out.length ? out : Arrays.copyOf(out, c);
    }

    /**
     * Whether {@code region} touches {@code box} without holding all of it. Only a cuboid's bounding
     * box is its shape, so any other shape touching the box counts as crossing it.
     */
    private static boolean crosses(final ProtectedRegion region, final int[] box) {
        final BlockVector3 min = region.getMinimumPoint();
        final BlockVector3 max = region.getMaximumPoint();
        if (max.x() < box[0] || min.x() > box[3] || max.y() < box[1] || min.y() > box[4]
            || max.z() < box[2] || min.z() > box[5]) {
            return false;
        }
        return !(region instanceof ProtectedCuboidRegion)
            || min.x() > box[0] || min.y() > box[1] || min.z() > box[2]
            || max.x() < box[3] || max.y() < box[4] || max.z() < box[5];
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
        flagsKept();
    }

    /**
     * Internal: mark this world for saving after an edit no shared set caches. Plugins should not call this.
     */
    public void uwgMarkUnsaved() {
        dirty.set(true);
    }

    /**
     * {@code region}'s priority or parent changed. Owners and members have no way back to their
     * region, so those edits reach an index at the next add or save instead. Shared sets go stale, as
     * both change how flags resolve. No flag was set or cleared, so the flag index stands.
     */
    void regionEdited(final ProtectedRegion region) {
        dirty.set(true);
        flagsKept();
        indexPut(region);
    }

    /**
     * {@code region} gained ({@code +1}), lost ({@code -1}) or kept ({@code 0}) the flag at
     * {@code index}, and gained, lost or kept a group qualifier. One count moves rather than every
     * region being walked again.
     */
    void flagEdited(final ProtectedRegion region, final int index, final int flagChange, final int groupChange) {
        dirty.set(true);
        countEdit(region, index, flagChange, groupChange);
        indexPut(region);
    }

    private synchronized void countEdit(final ProtectedRegion region, final int index, final int flagChange, final int groupChange) {
        final long before = flagIndexBuiltAt;
        final long now = FLAG_EDITS.incrementAndGet(this);
        if (before != now - 1) {
            return;
        }
        if (region.uwgOwner() == this) {
            usedFlagBits = count(usedFlagBits, index, flagChange);
            groupUses = Math.max(0, groupUses + groupChange);
            groupsInUse = groupUses > 0;
        }
        flagIndexBuiltAt = now;
    }

    /**
     * An edit that set no flag: shared sets go stale and the index stays current.
     */
    private synchronized void flagsKept() {
        final long before = flagIndexBuiltAt;
        final long now = FLAG_EDITS.incrementAndGet(this);
        if (before == now - 1) {
            flagIndexBuiltAt = now;
        }
    }

    /**
     * Takes {@code gone}'s flags out of the counts and puts {@code added}'s in. Called holding this
     * manager's monitor with the map edit, so a rebuild counts either both or neither. Left to a
     * rebuild when the index was already stale.
     */
    private void recount(final @Nullable ProtectedRegion gone, final @Nullable ProtectedRegion added) {
        final long before = flagIndexBuiltAt;
        final long now = FLAG_EDITS.incrementAndGet(this);
        if (before != now - 1) {
            return;
        }
        long[] bits = usedFlagBits;
        int groups = groupUses;
        if (gone != null) {
            for (final Flag<?> flag : gone.getFlags().keySet()) {
                bits = count(bits, flag.getIndex(), -1);
            }
            groups -= gone.getFlagGroups().size();
        }
        if (added != null) {
            for (final Flag<?> flag : added.getFlags().keySet()) {
                bits = count(bits, flag.getIndex(), 1);
            }
            groups += added.getFlagGroups().size();
        }
        groupUses = Math.max(0, groups);
        usedFlagBits = bits;
        groupsInUse = groupUses > 0;
        flagIndexBuiltAt = now;
    }

    /**
     * Moves the count of regions setting the flag at {@code index}, returning the bitset to publish:
     * {@code bits} itself unless the flag went from unused to used or back, then a copy, as readers
     * hold the published array. Called holding this manager's monitor.
     */
    private long[] count(final long[] bits, final int index, final int change) {
        if (index < 0 || change == 0) {
            return bits;
        }
        if (index >= flagUses.length) {
            flagUses = Arrays.copyOf(flagUses, Math.max(index + 1, Flags.count()));
        }
        final int was = flagUses[index];
        final int now = Math.max(0, was + change);
        flagUses[index] = now;
        if ((was > 0) == (now > 0)) {
            return bits;
        }
        final int word = index >> 6;
        final long[] out = Arrays.copyOf(bits, Math.max(bits.length, word + 1));
        if (now > 0) {
            out[word] |= 1L << index;
        } else {
            out[word] &= ~(1L << index);
        }
        return out;
    }

    /**
     * Whether any region in this world sets {@code flag} directly. Backed by a bitset over
     * {@link Flag#getIndex()} rebuilt lazily after a mutation, so a check is a volatile read plus one
     * word test — cheap enough to gate per-move flag reads, not just the once-a-second services.
     * Inherited flags count because the parent that defines them is itself a region.
     */
    public boolean anyRegionUses(final Flag<?> flag) {
        if (flagIndexBuiltAt != flagEdits) {
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

    /**
     * Whether any region in this world sets a flag in {@code mask}, a bitset from {@link Flags#mask}.
     * One staleness check for a group of flags a caller would otherwise test one by one.
     */
    public boolean anyRegionUsesAny(final long[] mask) {
        if (flagIndexBuiltAt != flagEdits) {
            rebuildFlagIndex();
        }
        final long[] bits = usedFlagBits;
        for (int i = 0, n = Math.min(bits.length, mask.length); i < n; i++) {
            if ((bits[i] & mask[i]) != 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts every region's flags from scratch. Readers that find the index stale wait here rather
     * than read the old bits, which would answer "unset" for a flag set a moment ago. Only a load
     * leaves it stale, since every later edit moves the counts itself.
     */
    private synchronized void rebuildFlagIndex() {
        final long target = flagEdits;
        if (flagIndexBuiltAt == target) {
            return;
        }
        final int[] uses = new int[Flags.count()];
        final long[] bits = new long[(Flags.count() >> 6) + 1];
        boolean any = false;
        int groups = 0;
        for (final ProtectedRegion region : regions.values()) {
            groups += region.getFlagGroups().size();
            for (final Flag<?> flag : region.getFlags().keySet()) {
                final int index = flag.getIndex();
                if (index >= 0 && index < uses.length) {
                    uses[index]++;
                    bits[index >> 6] |= 1L << index;
                    any = true;
                }
            }
        }
        flagUses = uses;
        groupUses = groups;
        usedFlagBits = any ? bits : EMPTY_BITS;
        groupsInUse = groups > 0;
        flagIndexBuiltAt = target;
    }

    /**
     * Whether any region in this world carries a group qualifier. Almost no server uses them, so this
     * lets flag resolution skip the per-region association check entirely rather than paying two map
     * lookups per region per flag on the hottest path in the plugin.
     */
    boolean anyFlagGroups() {
        if (flagIndexBuiltAt != flagEdits) {
            rebuildFlagIndex();
        }
        return groupsInUse;
    }

    public ApplicableRegionSet getApplicableRegions(final BlockVector3 point) {
        return getApplicableRegions(point.x(), point.y(), point.z());
    }

    /**
     * Kept to a call so it stays under C2's 325-byte limit for hot callees and inlines into the
     * query path, which calls it on every event.
     */
    public ApplicableRegionSet getApplicableRegions(final int x, final int y, final int z) {
        return cachedAt(x, y, z);
    }

    /**
     * How many regions overlap the chunk holding block {@code x, z} at any height. Past
     * {@link #DENSE_CANDIDATES} the chunk's lookups go uncached.
     */
    public int regionsOverlappingChunk(final int x, final int z) {
        return chunkCandidates(chunkKey(x, z)).regions().size();
    }

    /**
     * Above this many regions in one chunk, lookups there are not cached.
     */
    public static int cachedChunkLimit() {
        return DENSE_CANDIDATES;
    }

    private ApplicableRegionSet indexedAt(final SpatialIndex index, final int x, final int y, final int z) {
        final List<ProtectedRegion> owned = index.candidatesAt(x, y, z);
        if (owned.isEmpty() || !retainContaining(owned, x, y, z)) {
            return emptySet();
        }
        return new ApplicableRegionSet(owned, global, this,
            owned instanceof SpatialIndex.FlagResolver resolver ? resolver : null);
    }

    private ApplicableRegionSet cachedAt(final int x, final int y, final int z) {
        final ChunkCandidates chunk = chunkCandidates(chunkKey(x, z));
        final List<ProtectedRegion> candidates = chunk.regions();
        if (candidates.isEmpty()) {
            return emptySet();
        }
        final AtomicReferenceArray<@Nullable CachedSet> sets = chunk.sets();
        if (sets != null) {
            return sharedAt(chunk, sets, x, y, z);
        }
        final SpatialIndex index = spatialIndex;
        return index != null && candidates.size() > INDEXED_CANDIDATES
            ? indexedAt(index, x, y, z) : containing(candidates, x, y, z);
    }

    /**
     * The set for a point in a chunk with few candidates, shared by every point the same candidates
     * contain. Points in one chunk mostly fall in the same claim, so a lookup is a mask and a slot
     * read.
     *
     * <p>A shared set is stamped with {@link #flagEdits} read before it was built. It captures the
     * world's group-qualifier state and the regions' priority order, and changes to either count as
     * flag edits, so a stale set is rebuilt rather than served. A geometry edit retires the chunks it
     * reaches, sets with them.
     *
     * <p>The candidates' boxes are read from the chunk's flat copy, and only shapes other than a
     * cuboid go on to {@link ProtectedRegion#contains}, since a cuboid is its box.
     */
    private ApplicableRegionSet sharedAt(
        final ChunkCandidates chunk, final AtomicReferenceArray<@Nullable CachedSet> sets,
        final int x, final int y, final int z
    ) {
        final int[] b = chunk.bounds();
        final long[] columns = chunk.columns();
        long mask = 0L;
        if (columns != null) {
            for (long test = columns[((x & 15) << 4) | (z & 15)]; test != 0L; test &= test - 1) {
                final int i = Long.numberOfTrailingZeros(test);
                final int o = i * 6;
                if (y >= b[o + 1] && y <= b[o + 4]) {
                    mask |= 1L << i;
                }
            }
        } else {
            for (int i = 0, o = 0; o < b.length; i++, o += 6) {
                if (x >= b[o] && x <= b[o + 3] && y >= b[o + 1] && y <= b[o + 4] && z >= b[o + 2] && z <= b[o + 5]) {
                    mask |= 1L << i;
                }
            }
        }
        for (long test = mask & chunk.shaped(); test != 0L; test &= test - 1) {
            final int i = Long.numberOfTrailingZeros(test);
            if (!chunk.regions().get(i).contains(x, y, z)) {
                mask &= ~(1L << i);
            }
        }
        if (mask == 0L) {
            return emptySet();
        }
        final long edits = flagEdits;
        if (chunk.hashed()) {
            return denseAt(chunk, sets, mask, edits);
        }
        final CachedSet cached = sets.get((int) mask);
        if (cached != null && cached.edits() == edits) {
            return cached.set();
        }
        return buildShared(chunk.regions(), sets, (int) mask, mask, edits);
    }

    /**
     * {@link #sharedAt} for a dense chunk: the set sits at the mask's hash or the next free slot after
     * it, so the chunk keeps one set per mask its points produce, up to 64, with no two evicting each
     * other. A stale set is rebuilt in its own slot.
     */
    private ApplicableRegionSet denseAt(
        final ChunkCandidates chunk, final AtomicReferenceArray<@Nullable CachedSet> sets, final long mask, final long edits
    ) {
        final int home = (int) ((mask * 0x9E3779B97F4A7C15L) >>> (64 - DENSE_SLOT_BITS));
        final int last = (1 << DENSE_SLOT_BITS) - 1;
        for (int i = 0; i <= last; i++) {
            final int slot = (home + i) & last;
            final CachedSet cached = sets.get(slot);
            if (cached == null) {
                return buildShared(chunk.regions(), sets, slot, mask, edits);
            }
            if (cached.mask() == mask) {
                return cached.edits() == edits ? cached.set() : buildShared(chunk.regions(), sets, slot, mask, edits);
            }
        }
        return buildShared(chunk.regions(), sets, home, mask, edits);
    }

    private ApplicableRegionSet buildShared(
        final List<ProtectedRegion> candidates, final AtomicReferenceArray<@Nullable CachedSet> sets,
        final int slot, final long mask, final long edits
    ) {
        final List<ProtectedRegion> matches = new ArrayList<>(Long.bitCount(mask));
        for (int i = 0, n = candidates.size(); i < n; i++) {
            if ((mask & 1L << i) != 0L) {
                matches.add(candidates.get(i));
            }
        }
        final ApplicableRegionSet built = new ApplicableRegionSet(matches, global, this, edits, flagBits());
        sets.set(slot, new CachedSet(mask, edits, built));
        return built;
    }

    /**
     * How many flag edits this world has seen. A shared set compares it with the count it was built at.
     */
    long flagEditCount() {
        return flagEdits;
    }

    /**
     * The flag bitset, rebuilt first if an edit retired it. Built at least as late as the current
     * edit count, so a set stamped with an earlier count reads as stale rather than trusting it.
     */
    private long[] flagBits() {
        if (flagIndexBuiltAt != flagEdits) {
            rebuildFlagIndex();
        }
        return usedFlagBits;
    }

    /**
     * Two slots per chunk hash, so two busy chunks that share one both stay cached.
     */
    private ChunkCandidates chunkCandidates(final long key) {
        final ChunkTable current = chunkIndex;
        final ChunkTable cache = current != null ? current : installChunkIndex();
        final long edits = geometryEdits;
        final int slot = (int) ((key * CHUNK_HASH_MULTIPLIER) >>> (64 - CHUNK_CACHE_BITS)) & ~1;
        final ChunkCandidates first = cache.get(slot);
        if (first != null && first.key() == key && (first.checked == edits || untouched(first, edits))) {
            return first;
        }
        final ChunkCandidates second = cache.get(slot + 1);
        if (second != null && second.key() == key && (second.checked == edits || untouched(second, edits))) {
            return second;
        }
        return fillChunk(cache, slot, first, key, edits);
    }

    /**
     * Whether no geometry edit since {@code entry} was last known good reaches its chunk, marking it
     * good as of {@code edits} if so. After an edit each cached chunk pays this once, and only the
     * chunks an edit reaches are filled again.
     */
    private boolean untouched(final ChunkCandidates entry, final long edits) {
        final long since = entry.checked;
        if (since >= edits) {
            return true;
        }
        if (edits - since > RECENT_EDITS) {
            return false;
        }
        final int cx = (int) (entry.key() >> 32);
        final int cz = (int) entry.key();
        for (long seq = since + 1; seq <= edits; seq++) {
            final GeometryEdit edit = recentEdits.get((int) (seq & (RECENT_EDITS - 1)));
            if (edit == null || edit.seq() != seq || edit.reaches(cx, cz)) {
                return false;
            }
        }
        entry.checked = edits;
        return true;
    }

    /**
     * The new entry takes the first slot and the one it displaces moves to the second, dropping the
     * older entry there. A stale entry for the same chunk is overwritten rather than moved. Racing
     * fills can lose an entry or hold one twice, which costs a later fill and never a wrong answer, as
     * an entry is only served for its own key and only while no edit since its fill reaches it.
     */
    private ChunkCandidates fillChunk(
        final ChunkTable cache, final int slot, final @Nullable ChunkCandidates first, final long key, final long edits
    ) {
        final List<ProtectedRegion> built = buildChunkCandidates(key);
        final int n = built.size();
        final ChunkCandidates fresh;
        if (n > 0 && n <= SHARED_SET_CANDIDATES) {
            fresh = new ChunkCandidates(key, built, new AtomicReferenceArray<>(1 << n), false, bounds(built), null, shaped(built), edits);
        } else if (n > 0 && n <= DENSE_CANDIDATES) {
            final int[] bounds = bounds(built);
            fresh = new ChunkCandidates(key, built, new AtomicReferenceArray<>(1 << DENSE_SLOT_BITS), true, bounds,
                columns(bounds, key), shaped(built), edits);
        } else {
            fresh = new ChunkCandidates(key, built, null, false, NO_BOUNDS, null, 0, edits);
        }
        if (first != null && first.key() != key) {
            cache.set(slot + 1, first);
        }
        cache.set(slot, fresh);
        return fresh;
    }

    /**
     * Each region's bounding box as six ints, minimum then maximum. Region geometry is final and a
     * redefine retires every chunk either shape reaches, so the copy cannot go stale.
     */
    private static int[] bounds(final List<ProtectedRegion> regions) {
        final int[] out = new int[regions.size() * 6];
        for (int i = 0, o = 0, n = regions.size(); i < n; i++, o += 6) {
            final BlockVector3 min = regions.get(i).getMinimumPoint();
            final BlockVector3 max = regions.get(i).getMaximumPoint();
            out[o] = min.x();
            out[o + 1] = min.y();
            out[o + 2] = min.z();
            out[o + 3] = max.x();
            out[o + 4] = max.y();
            out[o + 5] = max.z();
        }
        return out;
    }

    /**
     * For each of the chunk's 256 block columns, a bit per candidate whose box covers it, so a lookup
     * in a dense chunk tests only those candidates' heights. 2 KB, built only for dense chunks.
     */
    private static long[] columns(final int[] bounds, final long key) {
        final int baseX = ((int) (key >> 32)) << 4;
        final int baseZ = ((int) key) << 4;
        final long[] out = new long[256];
        for (int i = 0, o = 0; o < bounds.length; i++, o += 6) {
            final int fromX = Math.max(bounds[o] - baseX, 0);
            final int toX = Math.min(bounds[o + 3] - baseX, 15);
            final int fromZ = Math.max(bounds[o + 2] - baseZ, 0);
            final int toZ = Math.min(bounds[o + 5] - baseZ, 15);
            for (int lx = fromX; lx <= toX; lx++) {
                for (int lz = fromZ; lz <= toZ; lz++) {
                    out[(lx << 4) | lz] |= 1L << i;
                }
            }
        }
        return out;
    }

    /**
     * A bit for each region whose shape is not its box, so a box hit still has to ask it.
     */
    private static long shaped(final List<ProtectedRegion> regions) {
        long mask = 0L;
        for (int i = 0, n = regions.size(); i < n; i++) {
            if (!(regions.get(i) instanceof ProtectedCuboidRegion)) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    private ApplicableRegionSet containing(final List<ProtectedRegion> candidates, final int x, final int y, final int z) {
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
        return new ApplicableRegionSet(matches, global, this, null);
    }

    /**
     * Drops, in place, the index's candidates that do not hold the point. Returns whether any are left.
     * The index over-reports by bounding box, so this is usually a pass that removes nothing.
     */
    private static boolean retainContaining(final List<ProtectedRegion> candidates, final int x, final int y, final int z) {
        int kept = 0;
        final int n = candidates.size();
        for (int i = 0; i < n; i++) {
            final ProtectedRegion region = candidates.get(i);
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            if (x < min.x() || x > max.x() || y < min.y() || y > max.y() || z < min.z() || z > max.z()
                || !region.contains(x, y, z)) {
                continue;
            }
            if (kept != i) {
                candidates.set(kept, region);
            }
            kept++;
        }
        if (kept < n) {
            candidates.subList(kept, n).clear();
        }
        return kept > 0;
    }

    /**
     * Whether {@link #stateAcross} can ever answer, so a caller can skip computing a bounding box.
     */
    boolean resolvesBoxes() {
        return spatialIndex != null;
    }

    /**
     * What {@link ApplicableRegionSet#queryState(StateFlag)} returns at every block of the box, or
     * {@code null} when that is not proven and each block has to be tested.
     */
    @Nullable State stateAcross(
        final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ,
        final StateFlag flag
    ) {
        final SpatialIndex index = spatialIndex;
        return index == null ? null : index.stateAcross(minX, minY, minZ, maxX, maxY, maxZ, flag, anyFlagGroups());
    }

    /**
     * Regions whose XZ bounding box overlaps the given chunk, the candidate set every query in
     * that chunk is narrowed to. Built once per chunk from the {@link RegionGrid}, then cached.
     * The Y bound and exact {@link ProtectedRegion#contains} are still tested per query, so non-cuboid
     * shapes resolve correctly. Global regions are excluded, as they are not spatial.
     */
    private List<ProtectedRegion> buildChunkCandidates(final long key) {
        final int minBx = ((int) (key >> 32)) << 4;
        final int maxBx = minBx + 15;
        final int minBz = ((int) key) << 4;
        final int maxBz = minBz + 15;
        final SpatialIndex index = spatialIndex;
        if (index != null) {
            final List<ProtectedRegion> hits = new ArrayList<>(
                index.intersecting(minBx, Short.MIN_VALUE, minBz, maxBx, Short.MAX_VALUE, maxBz));
            hits.removeIf(region -> region instanceof GlobalProtectedRegion);
            return hits.isEmpty() ? List.of() : hits;
        }
        RegionGrid cells = grid;
        if (cells == null) {
            synchronized (gridLock) {
                cells = grid;
                if (cells == null) {
                    cells = new RegionGrid(regions.values());
                    grid = cells;
                }
            }
        }
        List<ProtectedRegion> list = overlapping(cells.cell(minBx, minBz), minBx, maxBx, minBz, maxBz, null);
        list = overlapping(cells.large, minBx, maxBx, minBz, maxBz, list);
        return list == null ? List.of() : list;
    }

    private static @Nullable List<ProtectedRegion> overlapping(
        final ProtectedRegion[] regions, final int minBx, final int maxBx, final int minBz, final int maxBz,
        @Nullable List<ProtectedRegion> list
    ) {
        for (final ProtectedRegion region : regions) {
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
        return list;
    }

    private static boolean containsSame(final List<ProtectedRegion> regions, final ProtectedRegion region) {
        for (int i = 0, n = regions.size(); i < n; i++) {
            if (regions.get(i) == region) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSame(final ProtectedRegion[] regions, final int n, final ProtectedRegion region) {
        for (int i = 0; i < n; i++) {
            if (regions[i] == region) {
                return true;
            }
        }
        return false;
    }

    private static long chunkKey(final int x, final int z) {
        return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    }

    /**
     * A published cache slot: the chunk key it answers for, and the regions whose XZ bounds overlap
     * that chunk. The region list is a fresh snapshot never mutated after construction, so the entry
     * is safe to read without locking once stored via {@link AtomicReferenceArray#set} (a volatile
     * write that publishes it).
     */
    private static final class ChunkCandidates {
        private final long key;
        private final List<ProtectedRegion> regions;
        private final @Nullable AtomicReferenceArray<@Nullable CachedSet> sets;
        /**
         * Whether {@link #sets} is the hashed table of a dense chunk rather than indexed by mask.
         */
        private final boolean hashed;
        private final int[] bounds;
        private final long @Nullable [] columns;
        private final long shaped;
        /**
         * The {@link #geometryEdits} count this entry is known to hold at: read before it was filled,
         * then raised by reads that find no edit since reaching its chunk. Any value a thread sees was
         * proven by whoever wrote it, so a racing write can only cost another check.
         */
        volatile long checked;

        ChunkCandidates(
            final long key, final List<ProtectedRegion> regions, final @Nullable AtomicReferenceArray<@Nullable CachedSet> sets,
            final boolean hashed, final int[] bounds, final long @Nullable [] columns, final long shaped, final long checked
        ) {
            this.key = key;
            this.regions = regions;
            this.sets = sets;
            this.hashed = hashed;
            this.bounds = bounds;
            this.columns = columns;
            this.shaped = shaped;
            this.checked = checked;
        }

        long key() {
            return key;
        }

        List<ProtectedRegion> regions() {
            return regions;
        }

        @Nullable AtomicReferenceArray<@Nullable CachedSet> sets() {
            return sets;
        }

        boolean hashed() {
            return hashed;
        }

        int[] bounds() {
            return bounds;
        }

        long @Nullable [] columns() {
            return columns;
        }

        long shaped() {
            return shaped;
        }
    }

    private static final int[] NO_BOUNDS = new int[0];

    /**
     * A region set shared by the points of one chunk that the same candidates contain, which
     * candidates those are, and the {@link #flagEdits} count it was built at.
     */
    private record CachedSet(long mask, long edits, ApplicableRegionSet set) {}

    /**
     * Chunks with up to this many candidates index their sets by mask. Six keeps a chunk's table at
     * 64 slots, about 270 bytes, so the full 16384-chunk cache stays under 5 MB.
     */
    private static final int SHARED_SET_CANDIDATES = 6;

    /**
     * Chunks with more, up to one per bit of a long, share sets through a 64-slot table keyed by a hash
     * of the mask, since a shop row or a plot grid puts a dozen or more claims in one chunk. Beyond this
     * a chunk builds a set per lookup. Sixty-four rather than 32 because a production profile spent 90%
     * of its lookup time in chunks past 32, each asking the storage index per point.
     */
    private static final int DENSE_CANDIDATES = 64;
    private static final int DENSE_SLOT_BITS = 6;

    /**
     * With a storage index installed, chunks are filled from it once and points are answered here.
     * A chunk with more candidates than this asks the index per point instead of scanning them all.
     */
    private static final int INDEXED_CANDIDATES = DENSE_CANDIDATES;

    /**
     * A region came, went or was replaced. The grid takes the change in place, then the edit is logged
     * and published, which retires the cached chunks the regions' boxes reach as each is next read.
     * The rest of the cache stands.
     *
     * <p>Called after the storage index has the edit, so a fill that reads the index after the count
     * is published cannot miss it. A global region reaches every chunk, as shared sets hold it.
     */
    private void geometryChanged(final @Nullable ProtectedRegion gone, final @Nullable ProtectedRegion added) {
        synchronized (gridLock) {
            final RegionGrid cells = grid;
            if (cells != null) {
                if (gone != null) {
                    cells.remove(gone);
                }
                if (added != null) {
                    cells.add(added);
                }
            }
            if (gone instanceof GlobalProtectedRegion || added instanceof GlobalProtectedRegion) {
                logEdit(EVERYWHERE);
                return;
            }
            if (gone != null) {
                logEdit(GeometryEdit.of(gone));
            }
            if (added != null) {
                logEdit(GeometryEdit.of(added));
            }
        }
    }

    /**
     * Logs {@code reach} as the next edit, then publishes the count. Readers take the count first and
     * then the log, so every edit up to the count they read is already there. Called under the grid lock.
     */
    private void logEdit(final GeometryEdit reach) {
        final long seq = geometryEdits + 1;
        recentEdits.set((int) (seq & (RECENT_EDITS - 1)), reach.at(seq));
        geometryEdits = seq;
    }

    /**
     * The chunks one geometry edit reaches, by chunk coordinate, and its place in the edit count.
     */
    private record GeometryEdit(long seq, int minCx, int minCz, int maxCx, int maxCz) {
        static GeometryEdit of(final ProtectedRegion region) {
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            return new GeometryEdit(0L, min.x() >> 4, min.z() >> 4, max.x() >> 4, max.z() >> 4);
        }

        GeometryEdit at(final long sequence) {
            return new GeometryEdit(sequence, minCx, minCz, maxCx, maxCz);
        }

        boolean reaches(final int cx, final int cz) {
            return cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz;
        }
    }

    private static final GeometryEdit EVERYWHERE =
        new GeometryEdit(0L, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    /**
     * Installs the table unless another query already did, so racing first queries share one.
     */
    private ChunkTable installChunkIndex() {
        final ChunkTable fresh = new ChunkTable();
        if (CHUNK_INDEX.compareAndSet(this, null, fresh)) {
            return fresh;
        }
        final ChunkTable raced = chunkIndex;
        return raced != null ? raced : fresh;
    }

    private static final class ChunkTable extends AtomicReferenceArray<@Nullable ChunkCandidates> {
        ChunkTable() {
            super(CHUNK_CACHE_SLOTS);
        }
    }

    /**
     * The world's regions by 64-block cell, so filling a chunk tests the regions near it instead of
     * every region in the world. A region wider than {@link #LARGE_CELLS} cells either way would sit
     * in a great many cells, so it goes in one list every fill tests instead.
     *
     * <p>Edited in place under the manager's grid lock and read without it: a cell's array and the
     * large list are replaced whole, never written into.
     */
    private static final class RegionGrid {

        private static final int CELL_SHIFT = 6;
        private static final int LARGE_CELLS = 16;
        private static final ProtectedRegion[] NONE = new ProtectedRegion[0];

        private final Map<Long, ProtectedRegion[]> cells;
        volatile ProtectedRegion[] large;

        RegionGrid(final Collection<ProtectedRegion> regions) {
            final Map<Long, List<ProtectedRegion>> lists = new HashMap<>();
            final List<ProtectedRegion> wide = new ArrayList<>();
            for (final ProtectedRegion region : regions) {
                if (region instanceof GlobalProtectedRegion) {
                    continue;
                }
                final BlockVector3 min = region.getMinimumPoint();
                final BlockVector3 max = region.getMaximumPoint();
                final int x0 = min.x() >> CELL_SHIFT;
                final int x1 = max.x() >> CELL_SHIFT;
                final int z0 = min.z() >> CELL_SHIFT;
                final int z1 = max.z() >> CELL_SHIFT;
                if (x1 - x0 >= LARGE_CELLS || z1 - z0 >= LARGE_CELLS) {
                    wide.add(region);
                    continue;
                }
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        lists.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(4)).add(region);
                    }
                }
            }
            final Map<Long, ProtectedRegion[]> built = new ConcurrentHashMap<>(lists.size() * 4 / 3 + 1);
            for (final Map.Entry<Long, List<ProtectedRegion>> entry : lists.entrySet()) {
                built.put(entry.getKey(), entry.getValue().toArray(NONE));
            }
            cells = built;
            large = wide.toArray(NONE);
        }

        /**
         * The regions in the cell holding block {@code x}, {@code z}. A chunk never straddles a cell.
         */
        ProtectedRegion[] cell(final int x, final int z) {
            final ProtectedRegion[] found = cells.get(key(x >> CELL_SHIFT, z >> CELL_SHIFT));
            return found != null ? found : NONE;
        }

        /**
         * Adds {@code region} unless it is already here, which it can be when the first build read the
         * region map after the region went in.
         */
        void add(final ProtectedRegion region) {
            if (region instanceof GlobalProtectedRegion) {
                return;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            final int x0 = min.x() >> CELL_SHIFT;
            final int x1 = max.x() >> CELL_SHIFT;
            final int z0 = min.z() >> CELL_SHIFT;
            final int z1 = max.z() >> CELL_SHIFT;
            if (x1 - x0 >= LARGE_CELLS || z1 - z0 >= LARGE_CELLS) {
                large = with(large, region);
                return;
            }
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    cells.merge(key(cx, cz), new ProtectedRegion[]{region}, (held, one) -> with(held, region));
                }
            }
        }

        void remove(final ProtectedRegion region) {
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            final int x0 = min.x() >> CELL_SHIFT;
            final int x1 = max.x() >> CELL_SHIFT;
            final int z0 = min.z() >> CELL_SHIFT;
            final int z1 = max.z() >> CELL_SHIFT;
            if (x1 - x0 >= LARGE_CELLS || z1 - z0 >= LARGE_CELLS) {
                large = without(large, region);
                return;
            }
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    cells.computeIfPresent(key(cx, cz), (k, held) -> {
                        final ProtectedRegion[] left = without(held, region);
                        return left.length == 0 ? null : left;
                    });
                }
            }
        }

        private static ProtectedRegion[] with(final ProtectedRegion[] held, final ProtectedRegion region) {
            if (containsSame(held, held.length, region)) {
                return held;
            }
            final ProtectedRegion[] out = Arrays.copyOf(held, held.length + 1);
            out[held.length] = region;
            return out;
        }

        private static ProtectedRegion[] without(final ProtectedRegion[] held, final ProtectedRegion region) {
            int n = 0;
            final ProtectedRegion[] out = new ProtectedRegion[held.length];
            for (final ProtectedRegion r : held) {
                if (r != region) {
                    out[n++] = r;
                }
            }
            return n == held.length ? held : n == 0 ? NONE : Arrays.copyOf(out, n);
        }

        private static long key(final int cx, final int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }
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
            cached = new ApplicableRegionSet(List.of(), g, this, null);
            emptySet = cached;
        }
        return cached;
    }
}
