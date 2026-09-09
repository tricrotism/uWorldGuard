// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Cache-only name/UUID resolution for the domain shim.
 *
 * <p>WorldGuard's {@code DefaultDomain} still exposes a name-keyed surface; uWorldGuard stores UUIDs
 * only. Every lookup here reads the server's in-memory profile cache and goes no further — no Mojang
 * call, no playerdata read — so a consumer calling {@code addPlayer(String)} on a name the server has
 * never seen, or reading the owners of a region full of players who have not logged in for months,
 * fails quietly rather than stalling the region thread it was called on.
 */
@NullMarked
public final class NameResolver {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private NameResolver() {
    }

    /**
     * The cached name for a UUID, or {@code null} when the server has never seen that player.
     *
     * <p>Online is a field read; offline goes to the in-memory profile cache and stops there.
     * Deliberately not {@code Bukkit.getOfflinePlayer(uuid).getName()}, which looks like the obvious
     * call and is not one: with no name on the profile it falls through to that player's
     * {@code playerdata/<uuid>.dat}, which it reads and gunzips on the calling thread. This is
     * reached from {@code DefaultDomain.getPlayers()} — public WorldGuard API, called from consumer
     * event handlers and commands — once per member, so that would be a region tick blocked on one
     * decompress per owner of the region.
     */
    public static @Nullable String name(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        final PlayerProfile profile = Bukkit.createProfile(uuid);
        profile.completeFromCache();
        return profile.getName();
    }

    /**
     * The UUID for a name, resolved from the local user cache only.
     */
    public static @Nullable UUID uuid(final String name) {
        final OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player == null ? null : player.getUniqueId();
    }

    /**
     * Warns once, for the whole server lifetime, that a name-keyed domain edit could not be resolved.
     */
    public static void warnUnresolved(final String member, final String name) {
        if (com.tricrotism.uworldguard.util.VerboseLogging.enabled() && WARNED.compareAndSet(false, true)) {
            Bukkit.getLogger().log(Level.WARNING,
                "[uWorldGuard] A plugin called " + member + " with the name '" + name
                    + "', which is not in this server's player cache. uWorldGuard stores region"
                    + " members by UUID, so the edit was dropped. This is logged once.");
        }
    }
}
