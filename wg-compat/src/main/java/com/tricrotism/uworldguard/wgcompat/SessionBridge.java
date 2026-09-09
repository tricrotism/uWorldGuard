// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The shim's session manager: real sessions, real handler instances, driven by uWorldGuard's own
 * movement tracker and player tick through {@link SessionDispatch}.
 *
 * <p>A consumer that registers a handler factory gets sessions with its handlers created and
 * attached, so {@code Session.getHandler(...)} answers, and the first registration arms the
 * dispatch seam. Until then {@link SessionDispatch#ACTIVE} is false and the movement path costs one
 * boolean read.
 */
public final class SessionBridge implements com.sk89q.worldguard.session.SessionManager,
    SessionDispatch.Sink {

    public static final SessionBridge INSTANCE = new SessionBridge();

    private static final Logger LOGGER = Logger.getLogger("uWorldGuard");
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private final CopyOnWriteArrayList<com.sk89q.worldguard.session.handler.Handler.Factory<
        ? extends com.sk89q.worldguard.session.handler.Handler>> factories = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<UUID, com.sk89q.worldguard.session.Session> sessions = new ConcurrentHashMap<>();

    private SessionBridge() {
    }

    /**
     * A new session is initialized before it is handed out, so a handler sees the player's starting
     * region set even when it was registered after they logged in. Initialisation runs outside the
     * map operation: a handler is free to ask the manager for the session it is being initialised
     * with, which inside {@code computeIfAbsent} would be a re-entrant update of the same key.
     */
    @Override
    public com.sk89q.worldguard.session.Session get(final com.sk89q.worldguard.LocalPlayer player) {
        final java.util.UUID uuid = player.getUniqueId();
        final com.sk89q.worldguard.session.Session cached = sessions.get(uuid);
        if (cached != null) {
            return cached;
        }
        SessionDispatch.install(this);
        final com.sk89q.worldguard.session.Session created = newSession();
        final com.sk89q.worldguard.session.Session existing = sessions.putIfAbsent(uuid, created);
        if (existing != null) {
            return existing;
        }
        created.initialize(player);
        return created;
    }

    @Override
    public com.sk89q.worldguard.session.Session getIfPresent(final com.sk89q.worldguard.LocalPlayer player) {
        return sessions.get(player.getUniqueId());
    }

    @Override
    @Deprecated
    public com.sk89q.worldguard.session.Session createSession(final com.sk89q.worldguard.LocalPlayer player) {
        return get(player);
    }

    /**
     * Answers with uWorldGuard's own bypass state, which the plugin supplies through
     * {@link WgCompatBridge#bypassCheck(Predicate)}. A player who is not online cannot be bypassing.
     */
    @Override
    public boolean hasBypass(
        final com.sk89q.worldguard.LocalPlayer player, final com.sk89q.worldedit.world.World world
    ) {
        final org.bukkit.entity.Player bukkit = org.bukkit.Bukkit.getPlayer(player.getUniqueId());
        return bukkit != null && WgCompatBridge.hasBypass(bukkit);
    }

    @Override
    public boolean registerHandler(
        final com.sk89q.worldguard.session.handler.Handler.Factory<
            ? extends com.sk89q.worldguard.session.handler.Handler> factory,
        final com.sk89q.worldguard.session.handler.Handler.Factory<
            ? extends com.sk89q.worldguard.session.handler.Handler> after) {
        if (factory == null) {
            return false;
        }
        final int index = after == null ? -1 : factories.indexOf(after);
        if (index < 0) {
            factories.add(factory);
        } else {
            factories.add(index + 1, factory);
        }
        discardSessions();
        SessionDispatch.install(this);
        SessionDispatch.ACTIVE = WgCompatBridge.active();
        if (com.tricrotism.uworldguard.util.VerboseLogging.enabled() && ANNOUNCED.compareAndSet(false, true)) {
            LOGGER.log(Level.INFO, "A plugin registered a WorldGuard session handler ({0});"
                    + " uWorldGuard is now dispatching movement to session handlers.",
                factory.getClass().getName());
        }
        return true;
    }

    @Override
    public boolean unregisterHandler(
        final com.sk89q.worldguard.session.handler.Handler.Factory<
            ? extends com.sk89q.worldguard.session.handler.Handler> factory) {
        final boolean removed = factories.remove(factory);
        if (removed) {
            discardSessions();
            SessionDispatch.ACTIVE = !factories.isEmpty() && WgCompatBridge.active();
        }
        return removed;
    }

    @Override
    public boolean customHandlersRegistered() {
        return !factories.isEmpty();
    }

    @Override
    public void resetState(final com.sk89q.worldguard.LocalPlayer player) {
        final com.sk89q.worldguard.session.Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.uninitialize(player);
        }
    }

    @Override
    public void resetAllStates() {
        discardSessions();
    }

    /**
     * Drops every session, running its handlers' {@code uninitialize} first.
     *
     * <p>A handler that granted flight, invulnerability or a potion effect reverts it there. Simply
     * clearing the map — which is what registering a handler used to do — leaves the player holding
     * whatever the discarded handler gave them, with nothing left that knows to take it back.
     */
    private void discardSessions() {
        final java.util.Iterator<java.util.Map.Entry<UUID, com.sk89q.worldguard.session.Session>>
            entries = sessions.entrySet().iterator();
        while (entries.hasNext()) {
            final java.util.Map.Entry<UUID, com.sk89q.worldguard.session.Session> entry = entries.next();
            entries.remove();
            final org.bukkit.entity.Player bukkit = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (bukkit == null) {
                continue;
            }
            final com.sk89q.worldguard.session.Session session = entry.getValue();
            if (org.bukkit.Bukkit.getServer().isOwnedByCurrentRegion(bukkit)) {
                session.uninitialize((com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(bukkit));
            } else if (WgCompatBridge.active()) {
                bukkit.getScheduler().run(WgCompatBridge.plugin(),
                    _ -> session.uninitialize(
                        (com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(bukkit)), null);
            }
        }
    }

    /**
     * Drops every session on the way out, running its handlers' {@code uninitialize} for the players
     * the disabling thread owns.
     *
     * <p>Every session goes, owned or not — this manager is a singleton, so anything left in the map
     * would be handed to a consumer as its session after the next enable, holding region state from
     * before the disable. Only the owned ones can be reverted, though: under regionized
     * threading no API reaches into another region synchronously, and a task scheduled there never
     * runs, because the plugin's tasks are cancelled with it. What that leaves unreverted is the same
     * remainder {@code MovementListener.shutdown} leaves, and for the same reason.
     */
    @Override
    public void shutdown() {
        final java.util.Iterator<java.util.Map.Entry<UUID, com.sk89q.worldguard.session.Session>>
            entries = sessions.entrySet().iterator();
        while (entries.hasNext()) {
            final java.util.Map.Entry<UUID, com.sk89q.worldguard.session.Session> entry = entries.next();
            entries.remove();
            final org.bukkit.entity.Player bukkit = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (bukkit != null && org.bukkit.Bukkit.getServer().isOwnedByCurrentRegion(bukkit)) {
                entry.getValue().uninitialize(
                    (com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(bukkit));
            }
        }
        factories.clear();
    }

    /**
     * Re-arms dispatch for handler factories that outlived a disable, called when uWorldGuard binds
     * the compat layer again.
     *
     * <p>{@code ACTIVE} is cleared on the way out, and only {@code registerHandler} ever set it. A
     * consumer that registered before an in-place disable and enable therefore had a manager that
     * still listed its factories and still answered {@code customHandlersRegistered()}, while every
     * movement gate read false and its handlers were never dispatched again.
     */
    static void rearm() {
        SessionDispatch.ACTIVE = !INSTANCE.factories.isEmpty() && WgCompatBridge.active();
    }

    @Override
    public org.bukkit.Location testMove(
        final org.bukkit.entity.@NonNull Player player,
        final org.bukkit.@NonNull Location from,
        final org.bukkit.@NonNull Location to,
        final SessionDispatch.@NonNull Move type
    ) {
        final com.sk89q.worldguard.LocalPlayer local =
            (com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(player);
        final com.sk89q.worldedit.util.Location denied = get(local).uwgTestMoveTo(local,
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(from),
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(to),
            moveType(type));
        return denied == null ? null : com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(denied);
    }

    private static com.sk89q.worldguard.session.MoveType moveType(final SessionDispatch.Move type) {
        return switch (type) {
            case SWIM -> com.sk89q.worldguard.session.MoveType.SWIM;
            case GLIDE -> com.sk89q.worldguard.session.MoveType.GLIDE;
            case RIDE -> com.sk89q.worldguard.session.MoveType.RIDE;
            case EMBARK -> com.sk89q.worldguard.session.MoveType.EMBARK;
            case TELEPORT -> com.sk89q.worldguard.session.MoveType.TELEPORT;
            case RESPAWN -> com.sk89q.worldguard.session.MoveType.RESPAWN;
            case MOVE -> com.sk89q.worldguard.session.MoveType.MOVE;
        };
    }

    @Override
    public void tick(final org.bukkit.entity.@NonNull Player player) {
        final com.sk89q.worldguard.LocalPlayer local =
            (com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(player);
        get(local).tick(local);
    }

    /**
     * Creating the session is what initializes it, so this only has to ask for one.
     */
    @Override
    public void initialize(final org.bukkit.entity.@NonNull Player player) {
        get((com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(player));
    }

    /**
     * Runs the handlers' {@code uninitialize} and drops the session, so a returning player starts
     * from a clean one rather than state captured before they left.
     */
    @Override
    public void uninitialize(final org.bukkit.entity.@NonNull Player player) {
        final com.sk89q.worldguard.session.Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.uninitialize((com.sk89q.worldguard.LocalPlayer) PlayerWrapping.wrap(player));
        }
    }

    private com.sk89q.worldguard.session.Session newSession() {
        final com.sk89q.worldguard.session.Session session =
            new com.sk89q.worldguard.session.Session(this);
        final List<com.sk89q.worldguard.session.handler.Handler.Factory<
            ? extends com.sk89q.worldguard.session.handler.Handler>> snapshot = factories;
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            session.register(snapshot.get(i).create(session));
        }
        return session;
    }
}
