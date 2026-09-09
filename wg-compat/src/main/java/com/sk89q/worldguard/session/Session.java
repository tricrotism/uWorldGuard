// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.session;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.session.handler.Handler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One player's live session state and the handlers attached to it.
 *
 * <p>uWorldGuard creates real sessions, registers the handlers a consumer asked for, and drives
 * them from its own movement tracker and player tick — see
 * {@code com.tricrotism.uworldguard.wgcompat.SessionDispatch}.
 */
public class Session {

    /**
     * Handler classes already reported as throwing, so one broken handler logs once rather than on
     * every movement. Bounded by the number of handler classes registered on the server.
     */
    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private final SessionManager manager;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();

    private volatile boolean bypassDisabled;

    public Session(final SessionManager manager) {
        this.manager = manager;
    }

    public SessionManager getManager() {
        return manager;
    }

    public void register(final Handler handler) {
        handlers.add(handler);
    }

    @SuppressWarnings("unchecked")
    public <T extends Handler> T getHandler(final Class<T> type) {
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            if (type.isInstance(handler)) {
                return (T) handler;
            }
        }
        return null;
    }

    public boolean hasBypassDisabled() {
        return bypassDisabled;
    }

    public void setBypassDisabled(final boolean disabled) {
        this.bypassDisabled = disabled;
    }

    public void initialize(final LocalPlayer player) {
        final Location location = player.getLocation();
        final ApplicableRegionSet set = regionsAt(location);
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            try {
                handler.initialize(player, location, set);
            } catch (final RuntimeException | LinkageError e) {
                threw(handler, "initialize", e);
            }
        }
    }

    public void uninitialize(final LocalPlayer player) {
        final Location location = player.getLocation();
        final ApplicableRegionSet set = regionsAt(location);
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            try {
                handler.uninitialize(player, location, set);
            } catch (final RuntimeException | LinkageError e) {
                threw(handler, "uninitialize", e);
            }
        }
    }

    public void resetState(final LocalPlayer player) {
        uninitialize(player);
        initialize(player);
    }

    public void tick(final LocalPlayer player) {
        final ApplicableRegionSet set = regionsAt(player.getLocation());
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            try {
                handler.tick(player, set);
            } catch (final RuntimeException | LinkageError e) {
                threw(handler, "tick", e);
            }
        }
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.SESSION_DISPATCHES.increment();
    }

    public boolean isInvincible(final LocalPlayer player) {
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            final StateFlag.State state;
            try {
                state = handler.getInvincibility(player);
            } catch (final RuntimeException | LinkageError e) {
                threw(handler, "getInvincibility", e);
                continue;
            }
            if (state != null) {
                return state == StateFlag.State.ALLOW;
            }
        }
        return false;
    }

    public Location testMoveTo(final LocalPlayer player, final Location to, final MoveType moveType) {
        return testMoveTo(player, to, moveType, false);
    }

    /**
     * The location the player should end up at, or {@code null} when no handler objected. A forced
     * move skips the handlers entirely, as does a movement type that cannot be canceled.
     */
    public Location testMoveTo(
        final LocalPlayer player, final Location to, final MoveType moveType, final boolean forced
    ) {
        if (forced) {
            return null;
        }
        return uwgTestMoveTo(player, player.getLocation(), to, moveType);
    }

    /**
     * Internal: the same test with the origin supplied rather than read back off the player, which
     * is what uWorldGuard's polled movement tracker has and a {@code PlayerMoveEvent} gives for
     * free. Runs the handlers' {@code testMoveTo}, then — only when the region set actually changed
     * — their {@code onCrossBoundary}.
     *
     * <p>A movement that cannot be cancelled (a respawn) still runs every handler, so their entry
     * and exit bookkeeping stays correct; only the veto is dropped. Stopping short would leave a
     * handler believing the player is still in the region they respawned out of.
     */
    public Location uwgTestMoveTo(
        final LocalPlayer player, final Location from, final Location to, final MoveType moveType
    ) {
        if (handlers.isEmpty()) {
            return null;
        }
        final boolean cancellable = moveType.isCancellable();
        final ApplicableRegionSet toSet = regionsAt(to);
        for (int i = 0, n = handlers.size(); i < n; i++) {
            final Handler handler = handlers.get(i);
            final boolean allowed;
            try {
                allowed = handler.testMoveTo(player, from, to, toSet, moveType);
            } catch (final RuntimeException | LinkageError e) {
                threw(handler, "testMoveTo", e);
                continue;
            }
            if (!allowed && cancellable) {
                return from;
            }
        }

        final ApplicableRegionSet fromSet = regionsAt(from);
        if (fromSet.size() == 0 && toSet.size() == 0) {
            com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.SESSION_DISPATCHES.increment();
            return null;
        }
        final Set<ProtectedRegion> toRegions = setOf(toSet);
        final Set<ProtectedRegion> fromRegions = setOf(fromSet);
        if (!fromRegions.equals(toRegions)) {
            final Set<ProtectedRegion> entered = difference(toRegions, fromRegions);
            final Set<ProtectedRegion> exited = difference(fromRegions, toRegions);
            for (int i = 0, n = handlers.size(); i < n; i++) {
                final Handler handler = handlers.get(i);
                final boolean allowed;
                try {
                    allowed = handler.onCrossBoundary(player, from, to, toSet, entered, exited, moveType);
                } catch (final RuntimeException | LinkageError e) {
                    threw(handler, "onCrossBoundary", e);
                    continue;
                }
                if (!allowed && cancellable) {
                    return from;
                }
            }
        }

        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.SESSION_DISPATCHES.increment();
        return null;
    }

    /**
     * Records a handler that threw and carries on without it, once per handler class.
     *
     * <p>These handlers belong to other plugins, and they run inside uWorldGuard's own movement and
     * tick paths. One of them throwing used to take the rest of the pass with it: the tick stopped
     * before uWorldGuard applied heal, feed and the time and weather locks, and on the polled path
     * the tracker never recorded the position it had just moved to, so the same crossing was
     * processed again on every poll. A thrower is treated as raising no objection.
     */
    private static void threw(final Handler handler, final String stage, final Throwable error) {
        final Class<?> type = handler.getClass();
        if (REPORTED.add(type)) {
            Logger.getLogger("uWorldGuard").log(Level.WARNING, "[wg-compat] Session handler "
                + type.getName() + " threw from " + stage + "; continuing without it. This is a bug"
                + " in the plugin that registered the handler, not in uWorldGuard.", error);
        }
    }

    private static Set<ProtectedRegion> setOf(final ApplicableRegionSet set) {
        final Set<ProtectedRegion> regions = new HashSet<>(Math.max(2, set.size() * 2));
        for (final ProtectedRegion region : set) {
            regions.add(region);
        }
        return regions;
    }

    /**
     * Always a fresh set: the result is handed to consumer handlers, and returning {@code a} itself
     * when {@code b} is empty would alias the caller's live region set into their hands.
     */
    private static Set<ProtectedRegion> difference(final Set<ProtectedRegion> a, final Set<ProtectedRegion> b) {
        final Set<ProtectedRegion> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static ApplicableRegionSet regionsAt(final Location location) {
        return new RegionQuery().getApplicableRegions(location);
    }
}
