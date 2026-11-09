package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * The set of regions applicable at a point, with WorldGuard-style flag resolution:
 * higher priority overrides lower, ties favour {@code DENY}, flags inherit from the
 * parent chain, and the global region is the lowest-priority fallback.
 *
 * <p>Immutable snapshot, safe to build on a region thread and read without locking.
 */
@NullMarked
public final class ApplicableRegionSet {

    private static final Comparator<ProtectedRegion> PRIORITY_DESC =
        Comparator.comparingInt(ProtectedRegion::getPriority).reversed();

    // sorted by priority descending, never mutated
    private final List<ProtectedRegion> applicable;
    // lazy unmodifiable wrapper, see getRegions()
    private @Nullable List<ProtectedRegion> view;
    private final @Nullable ProtectedRegion global;
    private final @Nullable RegionManager owner;
    /**
     * Snapshot of whether this world uses group qualifiers, so resolution reads a field not a map.
     */
    private final boolean groupsInUse;
    /**
     * The storage backend's resolution for this block, used for every flag it accepts.
     */
    private final SpatialIndex.@Nullable FlagResolver resolver;
    /**
     * For a shared set, the manager's flag-edit count when it was built, or -1 for a one-lookup set.
     * While the count matches, the set may answer from {@link #usedBits} and {@link #resolved}. After
     * an edit it resolves live, so a set someone held on to stays correct.
     */
    private final long builtAt;
    /**
     * The world's flag bitset at {@link #builtAt}, read without the manager's two volatile loads.
     */
    private final long @Nullable [] usedBits;
    /**
     * Resolved states by flag index, for a shared set in a world without group qualifiers.
     * 0 unresolved, 1 unset, 2 allow, 3 deny. Racing threads write the same value, so plain writes are safe.
     */
    private byte @Nullable [] resolved;
    /**
     * Typed flag answers for a shared set, since a move asks for the same few flags over and over.
     * Eight slots filled first come, and a ninth flag resolves live.
     */
    private @Nullable Memo @Nullable [] values;
    /**
     * The WorldGuard API view of this set, kept so a shared set is wrapped once rather than on every
     * query a consumer plugin makes.
     */
    private volatile @Nullable Object compatView;

    public ApplicableRegionSet(final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global) {
        this(new ArrayList<>(applicable), global, null, null);
    }

    /**
     * A set the manager shares: as {@link #ApplicableRegionSet(List, ProtectedRegion, RegionManager,
     * SpatialIndex.FlagResolver)}, stamped with {@code builtAt} and the bitset current then.
     */
    ApplicableRegionSet(
        final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global,
        final RegionManager owner, final long builtAt, final long[] usedBits
    ) {
        sortByPriority(applicable);
        this.applicable = applicable;
        this.global = global;
        this.owner = owner;
        this.groupsInUse = owner.anyFlagGroups();
        this.resolver = null;
        this.builtAt = builtAt;
        this.usedBits = usedBits;
    }

    /**
     * Takes ownership of {@code applicable}: it is sorted in place and wrapped, never copied, so the
     * caller must not retain or mutate it afterwards. The manager builds a fresh list per query and
     * drops it immediately, so this removes a copy from the hottest path in the plugin. {@code owner}
     * is the manager the set came from, used to answer {@link #worldUses}.
     */
    ApplicableRegionSet(
        final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global,
        final @Nullable RegionManager owner, final SpatialIndex.@Nullable FlagResolver resolver
    ) {
        sortByPriority(applicable);
        this.applicable = applicable;
        this.global = global;
        this.owner = owner;
        this.groupsInUse = owner == null || owner.anyFlagGroups();
        this.resolver = resolver;
        this.builtAt = -1L;
        this.usedBits = null;
    }

    /**
     * Whether this shared set's snapshot still holds, at the cost of one volatile read.
     */
    private boolean fresh() {
        return builtAt >= 0 && owner != null && owner.flagEditCount() == builtAt;
    }

    /**
     * Priority descending, stable. A point is covered by a handful of regions, where an insertion
     * sort reading priorities directly beats {@code List.sort}'s array copy and comparator calls.
     * Larger lists still go through {@code List.sort}.
     */
    private static void sortByPriority(final List<ProtectedRegion> regions) {
        final int n = regions.size();
        if (n < 2) {
            return;
        }
        if (n > 16) {
            regions.sort(PRIORITY_DESC);
            return;
        }
        for (int i = 1; i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            final int priority = region.getPriority();
            int j = i - 1;
            while (j >= 0 && regions.get(j).getPriority() < priority) {
                regions.set(j + 1, regions.get(j));
                j--;
            }
            regions.set(j + 1, region);
        }
    }

    /**
     * Whether any region in the world this set came from sets {@code flag} at all — a bitset test, far
     * cheaper than {@link #queryValue}. Use it to skip resolving flags nobody on the server uses;
     * a {@code false} here guarantees {@code queryValue} would return {@code null}, because the global
     * region and every parent are themselves regions in that world's manager. Conservatively
     * {@code false} for a set not built by a manager.
     */
    public boolean worldUses(final Flag<?> flag) {
        return owner != null && owner.anyRegionUses(flag);
    }

    /**
     * {@link #worldUses} for a group of flags in one test, {@code mask} from {@code Flags.mask}.
     */
    public boolean worldUsesAny(final long[] mask) {
        return owner != null && owner.anyRegionUsesAny(mask);
    }

    /**
     * Internal (compat layer): the API view made for this set, or {@code null}. Plugins should not call this.
     */
    public @Nullable Object uwgCompatView() {
        return compatView;
    }

    /**
     * Internal (compat layer): keep {@code view} as this set's API view. Plugins should not call this.
     */
    public void uwgCompatView(final Object view) {
        compatView = view;
    }

    public boolean isEmpty() {
        return applicable.isEmpty();
    }

    /**
     * Whether something standing in {@code source} acts as a member here, WorldGuard's region-overlap
     * association for a cause with no player behind it: it stands in one of these regions or in a
     * parent of one, or both places are unclaimed.
     */
    public boolean overlaps(final ApplicableRegionSet source) {
        return overlaps(source.applicable, applicable);
    }

    static boolean overlaps(final List<ProtectedRegion> source, final List<ProtectedRegion> target) {
        if (target.isEmpty()) {
            return source.isEmpty();
        }
        for (int i = 0, n = target.size(); i < n; i++) {
            for (ProtectedRegion region = target.get(i); region != null; region = region.getParent()) {
                if (source.contains(region)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many regions apply here. Paired with {@link #get(int)} this walks the set without touching
     * {@link #getRegions()}, which would build an unmodifiable wrapper — worth avoiding on paths that
     * run per movement.
     */
    public int size() {
        return applicable.size();
    }

    /**
     * The region at {@code index}, highest priority first.
     */
    public ProtectedRegion get(final int index) {
        return applicable.get(index);
    }

    /**
     * The applicable regions, highest priority first. Unmodifiable.
     *
     * <p>The wrapper is built on first use rather than at construction: flag resolution reads the
     * backing list directly, so the many queries that only test a flag never allocate one. Two threads
     * racing here simply build identical wrappers over the same immutable list, which is harmless.
     */
    public List<ProtectedRegion> getRegions() {
        List<ProtectedRegion> cached = view;
        if (cached == null) {
            cached = Collections.unmodifiableList(applicable);
            view = cached;
        }
        return cached;
    }

    /**
     * The global fallback this set was built with — for the manager's empty-set cache.
     */
    @Nullable ProtectedRegion globalRegion() {
        return global;
    }

    /**
     * The group-qualifier state this set snapshotted — for the manager's empty-set cache, which has
     * to drop a cached set whose snapshot has gone stale.
     */
    boolean usesGroups() {
        return groupsInUse;
    }

    /**
     * Resolve a state flag for an actor that is not a player (or whose identity does not matter).
     * Group-qualified values are evaluated as WorldGuard does for non-players: as a non-member.
     */
    public State queryState(final StateFlag flag) {
        return queryState(flag, null);
    }

    /**
     * Resolve a state flag as it applies to {@code subject}. A value restricted to a
     * {@link RegionGroup} the subject is not in is skipped, so {@code pvp: deny} qualified to
     * non-members leaves members alone.
     */
    public State queryState(final StateFlag flag, final @Nullable UUID subject) {
        final State resolved = resolveState(flag, subject);
        return resolved != null ? resolved : flag.getDefault();
    }

    public boolean testState(final StateFlag flag) {
        return queryState(flag, null) == State.ALLOW;
    }

    public boolean testState(final StateFlag flag, final @Nullable UUID subject) {
        return queryState(flag, subject) == State.ALLOW;
    }

    /**
     * Whether {@code region}'s value for {@code flag} applies to {@code subject}.
     */
    private boolean appliesTo(
        final ProtectedRegion region, final Flag<?> flag, final @Nullable UUID subject
    ) {
        // One volatile read rules out the whole check for the overwhelmingly common case of a world
        // with no group qualifiers anywhere, keeping flag resolution exactly as cheap as before
        // qualifiers existed.
        if (!groupsActive()) {
            return true;
        }
        final RegionGroup group = region.getFlagGroup(flag);
        if (group == RegionGroup.ALL) {
            return true;
        }
        return group.contains(subject == null ? null : region.getAssociation(subject));
    }

    private boolean groupsActive() {
        return groupsInUse || owner != null && !fresh() && owner.anyFlagGroups();
    }

    /**
     * Whether the subject may build here. An explicit {@code build} flag wins; otherwise
     * membership of the highest-priority region decides; otherwise the presence of any
     * region means protected (deny).
     */
    public boolean canBuild(final @Nullable UUID subject) {
        final boolean passthrough = worldUsesOrUnknown(Flags.PASSTHROUGH);
        final boolean buildFlag = worldUsesOrUnknown(Flags.BUILD);
        ProtectedRegion top = null;
        int start = 0;
        if (passthrough) {
            for (int i = 0, n = applicable.size(); i < n; i++) {
                final ProtectedRegion region = applicable.get(i);
                if (region.getFlag(Flags.PASSTHROUGH) != State.ALLOW) {
                    top = region;
                    start = i;
                    break;
                }
            }
        } else if (!applicable.isEmpty()) {
            top = applicable.getFirst();
        }

        if (top == null) {
            final State g = global != null && appliesTo(global, Flags.BUILD, subject) ? global.getFlag(Flags.BUILD) : null;
            return g != State.DENY;
        }

        final int topPriority = top.getPriority();
        State explicit = null;
        boolean memberOfTop = false;
        for (int i = start, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            if (passthrough && i != start && region.getFlag(Flags.PASSTHROUGH) == State.ALLOW) {
                continue;
            }
            if (region.getPriority() != topPriority) {
                break;
            }
            if (buildFlag) {
                final State v = appliesTo(region, Flags.BUILD, subject) ? region.getFlag(Flags.BUILD) : null;
                if (v != null) {
                    explicit = explicit == State.DENY ? State.DENY : v;
                }
            }
            if (subject != null && region.isMember(subject)) {
                memberOfTop = true;
            }
        }

        if (explicit != null) {
            return explicit == State.ALLOW;
        }
        return memberOfTop;
    }

    /**
     * WorldGuard's build test, and the check every build-shaped protection should use: an explicit
     * {@code DENY} on {@code flag} refuses outright, an explicit {@code ALLOW} permits regardless of
     * membership, and only an unset flag falls back to {@link #canBuild}.
     *
     * <p>This is deliberately not {@code canBuild(subject) && testState(flag, subject)}. That form
     * makes an explicit {@code allow} inert in any region the subject is not a member of, because the
     * membership deny short-circuits it — so the standard "spawn where anyone may place blocks"
     * configuration silently did nothing. WorldGuard converts the build result's deny to nothing
     * before combining it with the flag, which is what the ordering below reproduces.
     *
     * <p>It also resolves {@code flag} without its default: a flag nobody set contributes nothing and
     * leaves membership to decide, rather than contributing the {@code allow} that most protection
     * flags default to.
     */
    public boolean testBuild(final @Nullable UUID subject, final StateFlag flag) {
        final State explicit = resolveState(flag, subject);
        if (explicit == State.DENY) {
            return false;
        }
        return explicit == State.ALLOW || canBuild(subject);
    }

    /**
     * {@link #testBuild(UUID, StateFlag)} over two flags, as WorldGuard combines them: either one
     * denying refuses, either one allowing permits, and only with both unset does membership decide.
     * A dedicated overload rather than varargs because the callers are per-interact.
     */
    public boolean testBuild(
        final @Nullable UUID subject, final StateFlag first, final StateFlag second
    ) {
        final State a = resolveState(first, subject);
        if (a == State.DENY) {
            return false;
        }
        final State b = resolveState(second, subject);
        if (b == State.DENY) {
            return false;
        }
        return a == State.ALLOW || b == State.ALLOW || canBuild(subject);
    }

    /**
     * The value some region actually sets for {@code flag}, or {@code null} if none does. Unlike
     * {@link #queryState} this never substitutes the flag's default, so a caller can tell "nobody
     * configured this" apart from "someone allowed it" and fall through to another rule.
     */
    public @Nullable State queryExplicitState(final StateFlag flag, final @Nullable UUID subject) {
        return resolveState(flag, subject);
    }

    /**
     * Whether {@code element} appears in any applicable region's set value for {@code flag} (deny-list
     * union: a region anywhere in the stack that lists the element wins, then the global fallback).
     */
    public <E> boolean flagSetContains(final Flag<Set<E>> flag, final E element) {
        if (!worldUsesOrUnknown(flag)) {
            return false;
        }
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final Set<E> set = applicable.get(i).getFlag(flag);
            if (set != null && set.contains(element)) {
                return true;
            }
        }
        final Set<E> g = global != null ? global.getFlag(flag) : null;
        return g != null && g.contains(element);
    }

    /**
     * Every applicable region's entries for {@code flag}, merged. Nothing shadows anything here, so
     * unlike {@link #queryValue} the highest-priority region cannot hide the rest. Two regions that
     * must agree on a name need to read the same name from either side.
     *
     * <p>Read-only: when a single region contributes, its own stored set is returned uncopied.
     *
     * @return an empty set when no region here sets the flag
     */
    public <E> Set<E> flagSetUnion(final Flag<Set<E>> flag) {
        if (!worldUsesOrUnknown(flag)) {
            return Set.of();
        }
        Set<E> union = null;
        boolean copied = false;
        for (int i = 0, n = applicable.size() + 1; i < n; i++) {
            final ProtectedRegion region = i < n - 1 ? applicable.get(i) : global;
            final Set<E> set = region == null ? null : region.getFlag(flag);
            if (set == null || set.isEmpty()) {
                continue;
            }
            if (union == null) {
                union = set;
            } else {
                if (!copied) {
                    union = new HashSet<>(union);
                    copied = true;
                }
                union.addAll(set);
            }
        }
        return union == null ? Set.of() : union;
    }

    /**
     * Whether any applicable region's entries for {@code flag} meet {@code candidates}. Allocates
     * nothing, so a caller holding one side's {@link #flagSetUnion} can ask this per position.
     */
    public <E> boolean flagSetIntersects(final Flag<Set<E>> flag, final Set<E> candidates) {
        if (candidates.isEmpty() || !worldUsesOrUnknown(flag)) {
            return false;
        }
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final Set<E> set = applicable.get(i).getFlag(flag);
            if (set != null && !Collections.disjoint(set, candidates)) {
                return true;
            }
        }
        final Set<E> g = global != null ? global.getFlag(flag) : null;
        return g != null && !Collections.disjoint(g, candidates);
    }

    /** Whether any contributing set contains the element for this subject. */
    public <E> boolean flagSetContains(final Flag<Set<E>> flag, final E element, final @Nullable UUID subject) {
        if (!groupsActive()) {
            return flagSetContains(flag, element);
        }
        if (!worldUsesOrUnknown(flag)) {
            return false;
        }
        for (int i = 0, n = applicable.size() + 1; i < n; i++) {
            final ProtectedRegion region = i < n - 1 ? applicable.get(i) : global;
            final Set<E> set = region != null && appliesTo(region, flag, subject) ? region.getFlag(flag) : null;
            if (set != null && set.contains(element)) {
                return true;
            }
        }
        return false;
    }

    /** Merge all sets applicable to the subject, without changing union precedence. */
    public <E> Set<E> flagSetUnion(final Flag<Set<E>> flag, final @Nullable UUID subject) {
        if (!groupsActive()) {
            return flagSetUnion(flag);
        }
        if (!worldUsesOrUnknown(flag)) {
            return Set.of();
        }
        Set<E> union = null;
        boolean copied = false;
        for (int i = 0, n = applicable.size() + 1; i < n; i++) {
            final ProtectedRegion region = i < n - 1 ? applicable.get(i) : global;
            final Set<E> set = region != null && appliesTo(region, flag, subject) ? region.getFlag(flag) : null;
            if (set == null || set.isEmpty()) {
                continue;
            }
            if (union == null) {
                union = set;
            } else {
                if (!copied) {
                    union = new HashSet<>(union);
                    copied = true;
                }
                union.addAll(set);
            }
        }
        return union == null ? Set.of() : union;
    }

    /** Whether any set applicable to the subject intersects the candidates. */
    public <E> boolean flagSetIntersects(final Flag<Set<E>> flag, final Set<E> candidates, final @Nullable UUID subject) {
        if (!groupsActive()) {
            return flagSetIntersects(flag, candidates);
        }
        if (candidates.isEmpty() || !worldUsesOrUnknown(flag)) {
            return false;
        }
        for (int i = 0, n = applicable.size() + 1; i < n; i++) {
            final ProtectedRegion region = i < n - 1 ? applicable.get(i) : global;
            final Set<E> set = region != null && appliesTo(region, flag, subject) ? region.getFlag(flag) : null;
            if (set != null && !Collections.disjoint(set, candidates)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve a typed (non-state) flag: highest-priority region that sets it wins.
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T queryValue(final Flag<T> flag) {
        if (!worldUsesOrUnknown(flag)) {
            return null;
        }
        if (usedBits == null || !fresh()) {
            return valueLive(flag);
        }
        Memo[] memo = values;
        if (memo == null) {
            memo = new Memo[8];
            values = memo;
        }
        for (int i = 0; i < memo.length; i++) {
            final Memo entry = memo[i];
            if (entry == null) {
                final T value = valueLive(flag);
                memo[i] = new Memo(flag, value);
                return value;
            }
            if (entry.flag() == flag) {
                return (T) entry.value();
            }
        }
        return valueLive(flag);
    }

    /**
     * One typed flag's answer in a shared set. The record's final fields publish it whole, so a
     * thread racing the write sees either nothing or the complete entry.
     */
    private record Memo(Flag<?> flag, @Nullable Object value) {}

    private <T> @Nullable T valueLive(final Flag<T> flag) {
        final SpatialIndex.FlagResolver r = resolver;
        if (r != null && r.resolves(flag)) {
            final ProtectedRegion top = r.highestSetting(flag);
            if (top == null) {
                return global != null ? global.getFlag(flag) : null;
            }
            final T v = top.getFlag(flag);
            if (v != null) {
                return v;
            }
        }
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final T v = applicable.get(i).getFlag(flag);
            if (v != null) {
                return v;
            }
        }
        return global != null ? global.getFlag(flag) : null;
    }

    /**
     * {@link #queryValue(Flag)} as it applies to {@code subject}: a value restricted to a
     * {@link RegionGroup} the subject is not in is skipped, and the next region down answers.
     */
    public <T> @Nullable T queryValue(final Flag<T> flag, final @Nullable UUID subject) {
        if (!groupsActive()) {
            return queryValue(flag);
        }
        if (!worldUsesOrUnknown(flag)) {
            return null;
        }
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            final T v = appliesTo(region, flag, subject) ? region.getFlag(flag) : null;
            if (v != null) {
                return v;
            }
        }
        return global != null && appliesTo(global, flag, subject) ? global.getFlag(flag) : null;
    }

    /**
     * Whether a region in this set's world could set {@code flag} at all. {@code true} for a set with
     * no owning manager, where nothing can be ruled out — the compat layer builds those.
     *
     * <p>Resolution starts here. A flag nobody has set anywhere in the world cannot be set on an
     * applicable region, on a parent, or on the global region, because all three live in the manager
     * the bitset was built from. One word test then replaces a walk of the region stack and a map
     * lookup per region, which is what every unset flag costs otherwise — and most flags are unset on
     * most servers.
     */
    private boolean worldUsesOrUnknown(final Flag<?> flag) {
        final long[] bits = usedBits;
        if (bits != null && fresh()) {
            final int index = flag.getIndex();
            final int word = index >> 6;
            return index >= 0 && word < bits.length && (bits[word] & (1L << index)) != 0L;
        }
        return owner == null || owner.anyRegionUses(flag);
    }

    private @Nullable State resolveState(final StateFlag flag, final @Nullable UUID subject) {
        if (usedBits == null || groupsInUse || !fresh()) {
            return resolveLive(flag, subject);
        }
        final int index = flag.getIndex();
        if (index < 0 || index >= Flags.count()) {
            return resolveLive(flag, subject);
        }
        byte[] memo = resolved;
        if (memo == null || index >= memo.length) {
            memo = new byte[Flags.count()];
            resolved = memo;
        }
        final byte known = memo[index];
        if (known != 0) {
            return known == 1 ? null : known == 2 ? State.ALLOW : State.DENY;
        }
        final State state = resolveLive(flag, subject);
        memo[index] = state == null ? (byte) 1 : state == State.ALLOW ? (byte) 2 : (byte) 3;
        return state;
    }

    private @Nullable State resolveLive(final StateFlag flag, final @Nullable UUID subject) {
        if (!worldUsesOrUnknown(flag)) {
            return null;
        }
        final SpatialIndex.FlagResolver r = resolver;
        if (r != null && r.resolves(flag)) {
            return r.resolveState(flag, subject, groupsActive());
        }
        boolean found = false;
        int bestPriority = 0;
        State result = null;
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            if (found && region.getPriority() < bestPriority) {
                break;
            }
            final State v = appliesTo(region, flag, subject) ? region.getFlag(flag) : null;
            if (v != null) {
                if (!found) {
                    found = true;
                    bestPriority = region.getPriority();
                    result = v;
                } else if (v == State.DENY) {
                    result = State.DENY;
                }
            }
        }
        if (found) {
            return result;
        }
        if (global == null || !appliesTo(global, flag, subject)) {
            return null;
        }
        return global.getFlag(flag);
    }
}
