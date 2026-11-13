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

    /**
     * Built on first use and never written into, so a view shared by every query in one claim can be
     * read from any thread. Both are unmodifiable for the same reason.
     */
    private volatile List<ProtectedRegion> regionList;
    private volatile Set<ProtectedRegion> regionSet;

    /**
     * The view of {@code backing}, made once per engine set. A shared set answers every query in its
     * claim, so consumer plugins querying per event do not allocate a view each time.
     */
    public static WrappedRegionSet of(
        final com.tricrotism.uworldguard.region.ApplicableRegionSet backing,
        final com.tricrotism.uworldguard.region.RegionManager manager
    ) {
        if (backing.uwgCompatView() instanceof WrappedRegionSet view && view.manager == manager) {
            return view;
        }
        final WrappedRegionSet view = new WrappedRegionSet(backing, manager);
        backing.uwgCompatView(view);
        return view;
    }

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

    /**
     * Internal: the API view of the engine region at {@code index}, without building the whole list.
     */
    public ProtectedRegion uwgWrap(final int index) {
        return RegionAdapters.region(backing.get(index), manager);
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
            cached = Collections.unmodifiableSet(new LinkedHashSet<>(regions));
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
            if (hasGroupDefault(flag)) {
                return walkValue(flag, engine, uuidOf(subject));
            }
            final V value = (V) FlagBridge.toShimValue(flag, backing.queryValue(engine, uuidOf(subject)));
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
        final UUID uuid = uuidOf(subject);
        final List<V> values = new ArrayList<>(4);
        for (int i = 0, n = backing.size(); i < n; i++) {
            addValue(values, flag, engine, backing.get(i), uuid);
        }
        if (values.isEmpty() && manager != null) {
            final com.tricrotism.uworldguard.region.ProtectedRegion global =
                manager.getRegion(com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID);
            if (global != null) {
                addValue(values, flag, engine, global, uuid);
            }
        }
        return values;
    }

    /**
     * Adds {@code region}'s value when its group qualifier covers {@code subject}, judged per region
     * as the engine's own resolution does.
     */
    @SuppressWarnings("unchecked")
    private static <V> void addValue(
        final List<V> values, final Flag<V> flag, final com.tricrotism.uworldguard.flags.Flag<Object> engine,
        final com.tricrotism.uworldguard.region.ProtectedRegion region, final UUID subject
    ) {
        final V value = valueIn(flag, engine, region, subject);
        if (value != null) {
            values.add(value);
        }
    }

    /**
     * {@code region}'s value for {@code flag} as it applies to {@code subject}, or {@code null}. An
     * unqualified value takes the shim flag's default group, which the engine does not have:
     * WorldGuard's {@code entry} and {@code exit} apply to non-members and its {@code teleport} and
     * {@code spawn} to members unless a region says otherwise.
     */
    @SuppressWarnings("unchecked")
    private static <V> V valueIn(
        final Flag<V> flag, final com.tricrotism.uworldguard.flags.Flag<Object> engine,
        final com.tricrotism.uworldguard.region.ProtectedRegion region,
        final UUID subject
    ) {
        final Object raw = region.getFlag(engine);
        if (raw == null) {
            return null;
        }
        final com.tricrotism.uworldguard.flags.RegionGroup explicit = region.getExplicitFlagGroup(engine);
        final com.tricrotism.uworldguard.flags.RegionGroup group =
            explicit != null ? explicit : FlagBridge.toEngineGroup(defaultGroup(flag));
        if (group != com.tricrotism.uworldguard.flags.RegionGroup.ALL && !group.contains(subject == null
            ? com.tricrotism.uworldguard.domain.Association.NON_MEMBER : region.getAssociation(subject))) {
            return null;
        }
        return (V) FlagBridge.toShimValue(flag, raw);
    }

    private static com.sk89q.worldguard.protection.flags.RegionGroup defaultGroup(final Flag<?> flag) {
        final com.sk89q.worldguard.protection.flags.RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        return groupFlag == null ? null : groupFlag.getDefault();
    }

    /**
     * Whether the engine's own resolution would differ from WorldGuard's for {@code flag}, because
     * its default group is not everyone.
     */
    private static boolean hasGroupDefault(final Flag<?> flag) {
        final com.sk89q.worldguard.protection.flags.RegionGroup group = defaultGroup(flag);
        return group != null && group != com.sk89q.worldguard.protection.flags.RegionGroup.ALL;
    }

    private com.tricrotism.uworldguard.region.ProtectedRegion engineGlobal() {
        return manager == null ? null
            : manager.getRegion(com.tricrotism.uworldguard.region.GlobalProtectedRegion.ID);
    }

    /**
     * WorldGuard's state resolution, region by region: the highest priority that has a value for the
     * subject wins, a deny beats an allow within it, then the global region, then the default.
     */
    private StateFlag.State walkState(
        final StateFlag flag,
        final com.tricrotism.uworldguard.flags.Flag<Object> engine,
        final UUID subject
    ) {
        boolean found = false;
        int best = 0;
        StateFlag.State result = null;
        for (int i = 0, n = backing.size(); i < n; i++) {
            final com.tricrotism.uworldguard.region.ProtectedRegion region = backing.get(i);
            if (found && region.getPriority() < best) {
                break;
            }
            final StateFlag.State value = valueIn(flag, engine, region, subject);
            if (value == null) {
                continue;
            }
            if (!found) {
                found = true;
                best = region.getPriority();
                result = value;
            } else if (value == StateFlag.State.DENY) {
                result = StateFlag.State.DENY;
            }
        }
        if (found) {
            return result;
        }
        final com.tricrotism.uworldguard.region.ProtectedRegion global = engineGlobal();
        final StateFlag.State value = global == null ? null : valueIn(flag, engine, global, subject);
        return value != null ? value : flag.getDefault();
    }

    private <V> V walkValue(
        final Flag<V> flag,
        final com.tricrotism.uworldguard.flags.Flag<Object> engine,
        final UUID subject
    ) {
        for (int i = 0, n = backing.size(); i < n; i++) {
            final V value = valueIn(flag, engine, backing.get(i), subject);
            if (value != null) {
                return value;
            }
        }
        final com.tricrotism.uworldguard.region.ProtectedRegion global = engineGlobal();
        final V value = global == null ? null : valueIn(flag, engine, global, subject);
        return value != null ? value : flag.getDefault();
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
        final com.tricrotism.uworldguard.flags.Flag<?> raw = engine;
        if (!(raw instanceof com.tricrotism.uworldguard.flags.StateFlag) || hasGroupDefault(flag)) {
            return walkState(flag, engine, subject);
        }
        if (raw instanceof com.tricrotism.uworldguard.flags.StateFlag stateFlag) {
            final com.tricrotism.uworldguard.flags.State explicit =
                backing.queryExplicitState(stateFlag, subject);
            if (explicit == null) {
                if (flag == com.sk89q.worldguard.protection.flags.Flags.BUILD) {
                    return backing.canBuild(subject) ? StateFlag.State.ALLOW : null;
                }
                return flag.getDefault();
            }
            return explicit == com.tricrotism.uworldguard.flags.State.ALLOW
                ? StateFlag.State.ALLOW
                : StateFlag.State.DENY;
        }
        return flag.getDefault();
    }

    private List<ProtectedRegion> regionList() {
        List<ProtectedRegion> cached = regionList;
        if (cached == null) {
            final int n = backing.size();
            final List<ProtectedRegion> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                regions.add(RegionAdapters.region(backing.get(i), manager));
            }
            cached = Collections.unmodifiableList(regions);
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
