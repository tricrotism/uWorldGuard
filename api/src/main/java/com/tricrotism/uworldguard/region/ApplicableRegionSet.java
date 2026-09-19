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

    public ApplicableRegionSet(final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global) {
        this(new ArrayList<>(applicable), global, null);
    }

    /**
     * Takes ownership of {@code applicable}: it is sorted in place and wrapped, never copied, so the
     * caller must not retain or mutate it afterwards. The manager builds a fresh list per query and
     * drops it immediately, so this removes a copy from the hottest path in the plugin. {@code owner}
     * is the manager the set came from, used to answer {@link #worldUses}.
     */
    ApplicableRegionSet(
        final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global,
        final @Nullable RegionManager owner
    ) {
        if (applicable.size() > 1) {
            applicable.sort(PRIORITY_DESC);
        }
        this.applicable = applicable;
        this.global = global;
        this.owner = owner;
        this.groupsInUse = owner == null || owner.anyFlagGroups();
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

    public boolean isEmpty() {
        return applicable.isEmpty();
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
        if (!groupsInUse) {
            return true;
        }
        final RegionGroup group = region.getFlagGroup(flag);
        if (group == RegionGroup.ALL) {
            return true;
        }
        return group.contains(subject == null ? null : region.getAssociation(subject));
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
        if (passthrough) {
            for (int i = 0, n = applicable.size(); i < n; i++) {
                final ProtectedRegion region = applicable.get(i);
                if (region.getFlag(Flags.PASSTHROUGH) != State.ALLOW) {
                    top = region;
                    break;
                }
            }
        } else if (!applicable.isEmpty()) {
            top = applicable.getFirst();
        }

        if (top == null) {
            final State g = global != null ? global.getFlag(Flags.BUILD) : null;
            return g != State.DENY;
        }

        final int topPriority = top.getPriority();
        State explicit = null;
        boolean memberOfTop = false;
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            if (passthrough && region.getFlag(Flags.PASSTHROUGH) == State.ALLOW) {
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
     * Resolve a typed (non-state) flag: highest-priority region that sets it wins.
     */
    public <T> @Nullable T queryValue(final Flag<T> flag) {
        if (!worldUsesOrUnknown(flag)) {
            return null;
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
        return owner == null || owner.anyRegionUses(flag);
    }

    private @Nullable State resolveState(final StateFlag flag, final @Nullable UUID subject) {
        if (!worldUsesOrUnknown(flag)) {
            return null;
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
