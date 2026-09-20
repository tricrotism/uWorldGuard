// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.MapFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * The shim view of an engine {@code com.tricrotism.uworldguard.region.ApplicableRegionSet}.
 *
 * <p>A subject the engine can identify — {@code null}, or a {@link UuidSubject} — is resolved by the
 * engine, which is the hot path: no region wrapping, no callbacks, no allocation beyond the answer.
 * Any other {@code RegionAssociable} forces the shim-side walk in {@link FlagQueryAlgorithms}, which
 * has to wrap every region so the associable can be asked about them.
 */
public final class WrappedRegionSet implements ApplicableRegionSet {

    private final com.tricrotism.uworldguard.region.ApplicableRegionSet backing;
    private final com.tricrotism.uworldguard.region.RegionManager manager;

    private List<ProtectedRegion> regionList;
    private Set<ProtectedRegion> regionSet;

    public WrappedRegionSet(
        final com.tricrotism.uworldguard.region.ApplicableRegionSet backing,
        final com.tricrotism.uworldguard.region.RegionManager manager
    ) {
        this.backing = backing;
        this.manager = manager;
    }

    /**
     * Internal: the engine set this wraps.
     */
    public com.tricrotism.uworldguard.region.ApplicableRegionSet uwgBacking() {
        return backing;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public Set<ProtectedRegion> getRegions() {
        Set<ProtectedRegion> cached = regionSet;
        if (cached == null) {
            final List<ProtectedRegion> regions = regionList();
            cached = new LinkedHashSet<>(regions);
            regionSet = cached;
        }
        return cached;
    }

    @Override
    public @NonNull Iterator<ProtectedRegion> iterator() {
        return regionList().iterator();
    }

    @Override
    public boolean isMemberOfAll(final LocalPlayer player) {
        final UUID uniqueId = player.getUniqueId();
        for (int i = 0, n = backing.size(); i < n; i++) {
            if (!backing.get(i).isMember(uniqueId)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isOwnerOfAll(final LocalPlayer player) {
        final UUID uniqueId = player.getUniqueId();
        for (int i = 0, n = backing.size(); i < n; i++) {
            if (!backing.get(i).isOwner(uniqueId)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public StateFlag.State queryState(final RegionAssociable subject, final StateFlag... flags) {
        if (flags.length == 0) {
            return null;
        }
        StateFlag.State result = null;
        if (engineResolvable(subject)) {
            final UUID uuid = uuidOf(subject);
            for (int i = 0; i < flags.length; i++) {
                final StateFlag.State state = engineState(flags[i], uuid);
                if (state == StateFlag.State.DENY) {
                    return StateFlag.State.DENY;
                }
                if (state == StateFlag.State.ALLOW) {
                    result = StateFlag.State.ALLOW;
                }
            }
            return result;
        }

        final List<ProtectedRegion> regions = regionList();
        final Association association = FlagQueryAlgorithms.association(subject, regions);
        final ProtectedRegion global = globalRegion();
        for (int i = 0; i < flags.length; i++) {
            final StateFlag.State state =
                FlagQueryAlgorithms.queryState(regions, global, association, flags[i]);
            if (state == StateFlag.State.DENY) {
                return StateFlag.State.DENY;
            }
            if (state == StateFlag.State.ALLOW) {
                result = StateFlag.State.ALLOW;
            }
        }
        return result;
    }

    @Override
    public boolean testState(final RegionAssociable subject, final StateFlag... flags) {
        return queryState(subject, flags) == StateFlag.State.ALLOW;
    }

    /**
     * Whether {@code subject} may build here — the shim's entry point for
     * {@code RegionQuery.testBuild}.
     */
    public boolean canBuild(final RegionAssociable subject) {
        if (engineResolvable(subject)) {
            return backing.canBuild(uuidOf(subject));
        }
        final List<ProtectedRegion> regions = regionList();
        return FlagQueryAlgorithms.canBuild(regions, globalRegion(),
            FlagQueryAlgorithms.association(subject, regions));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V queryValue(final RegionAssociable subject, final Flag<V> flag) {
        if (flag instanceof StateFlag stateFlag) {
            return (V) queryState(subject, stateFlag);
        }
        if (engineResolvable(subject)) {
            final com.tricrotism.uworldguard.flags.Flag<Object> engine = FlagBridge.engineFlag(flag);
            if (engine == null) {
                return flag.getDefault();
            }
            final V value = (V) FlagBridge.toShimValue(flag, backing.queryValue(engine));
            return value != null ? value : flag.getDefault();
        }
        final List<ProtectedRegion> regions = regionList();
        return FlagQueryAlgorithms.queryValue(regions, globalRegion(),
            FlagQueryAlgorithms.association(subject, regions), flag);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> Collection<V> queryAllValues(final RegionAssociable subject, final Flag<V> flag) {
        if (!engineResolvable(subject)) {
            final List<ProtectedRegion> regions = regionList();
            return FlagQueryAlgorithms.queryAllValues(regions, globalRegion(),
                FlagQueryAlgorithms.association(subject, regions), flag);
        }
        final com.tricrotism.uworldguard.flags.Flag<Object> engine = FlagBridge.engineFlag(flag);
        if (engine == null) {
            return List.of();
        }
        final List<V> values = new ArrayList<>(4);
        for (int i = 0, n = backing.size(); i < n; i++) {
            final Object raw = backing.get(i).getFlag(engine);
            if (raw != null) {
                final V value = (V) FlagBridge.toShimValue(flag, raw);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    @Override
    public <V, K> V queryMapValue(final RegionAssociable subject, final MapFlag<K, V> flag, final K key) {
        return queryMapValue(subject, flag, key, null);
    }

    @Override
    public <V, K> V queryMapValue(final RegionAssociable subject, final MapFlag<K, V> flag, final K key,
                                  final Flag<V> fallback) {
        final Map<K, V> map = queryValue(subject, flag);
        if (map != null) {
            final V value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return fallback == null ? null : queryValue(subject, fallback);
    }

    /**
     * Resolves a state flag the way WorldGuard's own query does: what some region actually set, and
     * the <em>shim</em> flag's default when nothing did.
     *
     * <p>The two defaults are not the same value. The engine's is never null, so asking it to resolve
     * an unset flag answered {@code DENY} where WorldGuard answers {@code null} — and a consumer's
     * "null means WorldGuard has no opinion, apply my own rule" branch never ran again. The other way
     * round, a flag a consumer registered is not an engine state flag at all, so it fell through to
     * the value path, which never applies a default: a plugin registering
     * {@code new StateFlag("theirs", true)} read {@code null} everywhere and switched itself off.
     */
    private StateFlag.State engineState(final StateFlag flag, final UUID subject) {
        final com.tricrotism.uworldguard.flags.Flag<Object> engine = FlagBridge.engineFlag(flag);
        if (engine == null) {
            return flag.getDefault();
        }
        // Via a wildcard: Flag<Object> and the engine's Flag<State> are provably distinct types, so
        // instanceof against the declared type does not compile.
        final com.tricrotism.uworldguard.flags.Flag<?> raw = engine;
        if (raw instanceof com.tricrotism.uworldguard.flags.StateFlag stateFlag) {
            final com.tricrotism.uworldguard.flags.State explicit =
                backing.queryExplicitState(stateFlag, subject);
            if (explicit == null) {
                return flag.getDefault();
            }
            return explicit == com.tricrotism.uworldguard.flags.State.ALLOW
                ? StateFlag.State.ALLOW
                : StateFlag.State.DENY;
        }
        final StateFlag.State value = (StateFlag.State) FlagBridge.toShimValue(flag, backing.queryValue(engine));
        return value != null ? value : flag.getDefault();
    }

    private List<ProtectedRegion> regionList() {
        List<ProtectedRegion> cached = regionList;
        if (cached == null) {
            final int n = backing.size();
            final List<ProtectedRegion> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                regions.add(RegionAdapters.region(backing.get(i), manager));
            }
            cached = regions;
            regionList = cached;
        }
        return cached;
    }

    /**
     * Internal: this world's global region, or {@code null} when there is none.
     */
    public ProtectedRegion uwgGlobalRegion() {
        return globalRegion();
    }

    private ProtectedRegion globalRegion() {
        if (manager == null) {
            return null;
        }
        final com.tricrotism.uworldguard.region.ProtectedRegion global =
            manager.getRegion(com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID);
        return global == null ? null : RegionAdapters.region(global, manager);
    }

    /**
     * Whether the engine can answer for this subject directly, skipping the shim-side walk.
     *
     * <p>{@code Associables.constant(NON_MEMBER)} counts. It is WorldGuard's own idiom for an actor
     * with no identity — a piston, a dispenser, an explosion, a plugin acting as the server — so it
     * arrives on per-block and per-damage handlers, and it means exactly what the engine's
     * {@code null} subject means: every group qualifier is evaluated as a non-member. Without this it
     * wrapped every applicable region per query to ask a constant.
     */
    private static boolean engineResolvable(final RegionAssociable subject) {
        return subject == null
            || subject instanceof UuidSubject
            || Associables.uwgIsConstantNonMember(subject);
    }

    private static UUID uuidOf(final RegionAssociable subject) {
        return subject instanceof UuidSubject uuidSubject ? uuidSubject.uwgUuid() : null;
    }
}
