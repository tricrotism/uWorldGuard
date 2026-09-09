// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The seam uWorldGuard's own listeners call to drive WorldGuard session handlers.
 *
 * <p>Nothing here names a {@code com.sk89q} type, in signature or in body: the plugin module must
 * stay loadable on a server with no WorldEdit, and {@link SessionBridge} — which does name them —
 * is only reachable through the {@link Sink} it installs. It installs one the first time a consumer
 * registers a handler factory, which cannot happen unless WorldEdit is present.
 *
 * <p>{@link #ACTIVE} is the hot-path gate: one volatile boolean read per movement event, false on
 * every server where no plugin registered a handler.
 */
@NullMarked
public final class SessionDispatch {

    /**
     * Why the player moved. Mirrors WorldGuard's {@code MoveType} without naming it, so the plugin
     * can classify a crossing without linking a WorldGuard class.
     */
    public enum Move {
        MOVE,
        SWIM,
        GLIDE,
        RIDE,
        EMBARK,
        TELEPORT,
        RESPAWN
    }

    /**
     * Bukkit-typed view of the session manager, so the plugin never links a WorldGuard class.
     */
    public interface Sink {

        /**
         * Where the player should end up, or {@code null} when no handler objected.
         */
        @Nullable Location testMove(Player player, Location from, Location to, Move type);

        void tick(Player player);

        void initialize(Player player);

        void uninitialize(Player player);

        /**
         * Drop every live session, reverting what its handlers granted. Called from uWorldGuard's
         * disable, on the disabling thread.
         */
        void shutdown();
    }

    /**
     * True only while the compat layer is bound and at least one handler factory is registered.
     * Gates the movement hot path.
     */
    public static volatile boolean ACTIVE;

    /**
     * True once anything has asked for a session, whether a handler factory was ever
     * registered. Reading sessions is public API and is the common consumer idiom, so quit cleanup
     * has to key off this and not off {@link #ACTIVE} — otherwise a plugin that only reads sessions
     * leaves one behind for every player who ever logged in.
     */
    public static volatile boolean TRACKING;

    private static volatile @Nullable Sink sink;

    private SessionDispatch() {
    }

    static void install(final Sink installed) {
        sink = installed;
        TRACKING = true;
    }

    public static @Nullable Location testMove(
        final Player player, final Location from, final Location to, final Move type
    ) {
        final Sink target = sink;
        return target == null ? null : target.testMove(player, from, to, type);
    }

    /**
     * How a player who moved under their own power is traveling. Three boolean reads off the player,
     * only on a crossing and only while the layer is armed.
     */
    public static Move selfMove(final Player player) {
        if (player.isGliding()) {
            return Move.GLIDE;
        }
        if (player.isSwimming()) {
            return Move.SWIM;
        }
        return player.isInsideVehicle() ? Move.RIDE : Move.MOVE;
    }

    public static void tick(final Player player) {
        final Sink target = sink;
        if (target != null) {
            target.tick(player);
        }
    }

    public static void initialize(final Player player) {
        final Sink target = sink;
        if (target != null) {
            target.initialize(player);
        }
    }

    public static void uninitialize(final Player player) {
        final Sink target = sink;
        if (target != null) {
            target.uninitialize(player);
        }
    }

    /**
     * Releases what the compat layer holds for a player who has left. Separate from
     * {@link #uninitialize} because that one only runs while a consumer has session handlers
     * registered, and a consumer that merely reads flags still had a wrapper built for it.
     *
     * <p>Gated on the layer being active so a server without WorldEdit never reaches
     * {@code PlayerWrapping}, whose initialiser resolves WorldEdit types. The gate is a volatile read,
     * and when it is false the call below is never executed and so never linked.
     */
    public static void forget(final Player player) {
        if (WgCompatBridge.active()) {
            PlayerWrapping.forget(player.getUniqueId());
        }
    }

    /**
     * The counterpart to {@link #install}, called from uWorldGuard's disable before the compat layer
     * is unbound — while the engine a handler's {@code uninitialize} may call back into is still
     * live.
     *
     * <p>Without it the sessions simply stayed, and a handler that granted flight, invulnerability or
     * a potion effect never got to take it back: the player kept it with nothing left that knew it
     * had been given. Same reason uWorldGuard undoes its own game-mode and speed overrides on the way
     * out rather than leaving them written into player data.
     */
    public static void shutdown() {
        final Sink target = sink;
        ACTIVE = false;
        TRACKING = false;
        sink = null;
        if (target != null) {
            target.shutdown();
        }
    }
}
