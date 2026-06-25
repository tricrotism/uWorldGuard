package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
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

    private final List<ProtectedRegion> applicable; // sorted by priority descending
    private final @Nullable ProtectedRegion global;

    public ApplicableRegionSet(final List<ProtectedRegion> applicable, final @Nullable ProtectedRegion global) {
        final List<ProtectedRegion> copy = new ArrayList<>(applicable);
        if (copy.size() > 1) {
            copy.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed());
        }
        this.applicable = Collections.unmodifiableList(copy);
        this.global = global;
    }

    public boolean isEmpty() {
        return applicable.isEmpty();
    }

    /**
     * The applicable regions, highest priority first. Unmodifiable.
     */
    public List<ProtectedRegion> getRegions() {
        return applicable;
    }

    /**
     * The global fallback this set was built with — for the manager's empty-set cache.
     */
    @Nullable ProtectedRegion globalRegion() {
        return global;
    }

    /**
     * Resolve a state flag without any membership consideration.
     */
    public State queryState(final StateFlag flag) {
        final State resolved = resolveState(flag);
        return resolved != null ? resolved : flag.getDefault();
    }

    public boolean testState(final StateFlag flag) {
        return queryState(flag) == State.ALLOW;
    }

    /**
     * Whether the subject may build here. An explicit {@code build} flag wins; otherwise
     * membership of the highest-priority region decides; otherwise the presence of any
     * region means protected (deny).
     */
    public boolean canBuild(final @Nullable UUID subject) {
        if (applicable.isEmpty()) {
            final State g = global != null ? global.getFlag(Flags.BUILD) : null;
            return g != State.DENY;
        }

        final int topPriority = applicable.getFirst().getPriority();
        State explicit = null;
        boolean memberOfTop = false;
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            if (region.getPriority() != topPriority) {
                break;
            }
            final State v = region.getFlag(Flags.BUILD);
            if (v != null) {
                explicit = explicit == State.DENY ? State.DENY : v;
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
     * Whether {@code element} appears in any applicable region's set value for {@code flag} (deny-list
     * union: a region anywhere in the stack that lists the element wins, then the global fallback).
     */
    public <E> boolean flagSetContains(final Flag<Set<E>> flag, final E element) {
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
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final T v = applicable.get(i).getFlag(flag);
            if (v != null) {
                return v;
            }
        }
        return global != null ? global.getFlag(flag) : null;
    }

    private @Nullable State resolveState(final StateFlag flag) {
        boolean found = false;
        int bestPriority = 0;
        State result = null;
        for (int i = 0, n = applicable.size(); i < n; i++) {
            final ProtectedRegion region = applicable.get(i);
            if (found && region.getPriority() < bestPriority) {
                break;
            }
            final State v = region.getFlag(flag);
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
        return global != null ? global.getFlag(flag) : null;
    }
}
