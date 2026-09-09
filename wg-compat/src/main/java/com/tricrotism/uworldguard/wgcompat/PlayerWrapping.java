// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Builds {@code com.sk89q.worldguard.LocalPlayer} instances.
 *
 * <p>An online player becomes a {@link LocalBukkitPlayer}, which extends WorldEdit's own
 * {@code BukkitPlayer}. Consumers cast to that class to get back to Bukkit, so the inheritance is
 * part of the contract. An offline player has no WorldEdit player to extend, so it stays a
 * {@link Proxy} that answers identity and permission questions and refuses the rest.
 *
 * <p>Both also implement {@link UuidSubject}, which is what keeps region queries made with a
 * {@code LocalPlayer} on the engine's UUID fast path.
 *
 * <p>This class is the only place the shim resolves a WorldEdit type from a static initialiser, and
 * it is loaded lazily — {@code WorldGuardPlugin} reaches it through {@link #wrap(Player)}, declared
 * to return {@link Object}, so a server without WorldEdit never loads it.
 */
public final class PlayerWrapping {

    private static final Class<?> LOCAL_PLAYER = com.sk89q.worldguard.LocalPlayer.class;

    private static final Class<?>[] INTERFACES = {LOCAL_PLAYER, UuidSubject.class};

    /**
     * Time-bounded rather than reference-bounded: nothing else holds a wrapper strongly, so weak
     * values would be collected between calls and defeat the cache. Staleness is handled by the
     * identity check in {@link #wrap(Player)}, not by the expiry.
     */
    private static final Cache<UUID, LocalBukkitPlayer> CACHE = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();

    private PlayerWrapping() {
    }

    /**
     * The {@code LocalPlayer} for an online player, cached per UUID. Declared to return
     * {@link Object} so callers that must stay loadable without WorldEdit can cast at the call site
     * instead of naming the type in their own descriptors.
     */
    public static Object wrap(final Player player) {
        final UUID uniqueId = player.getUniqueId();
        final LocalBukkitPlayer cached = CACHE.getIfPresent(uniqueId);
        if (cached != null && cached.bukkit() == player) {
            return cached;
        }
        CompatDiagnostics.WRAPS.increment();
        final LocalBukkitPlayer wrapped = new LocalBukkitPlayer(player);
        CACHE.put(uniqueId, wrapped);
        return wrapped;
    }

    /**
     * The {@code LocalPlayer} for an offline player. An online player resolves through
     * {@link #wrap(Player)}; otherwise the result answers identity and permission questions only,
     * and every WorldEdit method on it throws {@link UnsupportedOperationException}.
     */
    public static Object wrapOffline(final OfflinePlayer player) {
        final Player online = player.getPlayer();
        if (online != null) {
            return wrap(online);
        }
        CompatDiagnostics.WRAPS.increment();
        return createOffline(player);
    }

    /**
     * Drops the wrapper held for a player who has left. The expiry alone would release it eventually,
     * but until then the wrapper holds their {@code Player}, and through it the server's whole
     * entity and inventory graph for someone no longer on the server.
     */
    public static void forget(final UUID uniqueId) {
        CACHE.invalidate(uniqueId);
    }

    /**
     * A WorldEdit {@code Actor} for any command sender: a {@code LocalPlayer} for a player, and
     * WorldEdit's own console actor otherwise.
     */
    public static Object wrapSender(final CommandSender sender) {
        if (sender instanceof Player player) {
            return wrap(player);
        }
        return com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(Bukkit.getConsoleSender());
    }

    /**
     * The Bukkit sender behind a WorldEdit actor, or {@code null} when it is not one this shim can
     * unwrap.
     */
    public static CommandSender unwrap(final Object actor) {
        if (actor instanceof UuidSubject subject) {
            return Bukkit.getPlayer(subject.uwgUuid());
        }
        if (actor instanceof com.sk89q.worldedit.entity.Player player) {
            return com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player);
        }
        if (actor instanceof com.sk89q.worldedit.extension.platform.Actor typed && !typed.isPlayer()) {
            return Bukkit.getConsoleSender();
        }
        return null;
    }

    private static Object createOffline(final OfflinePlayer offline) {
        final Handler handler = new Handler(offline, offline.getUniqueId());
        return Proxy.newProxyInstance(LOCAL_PLAYER.getClassLoader(), INTERFACES, handler);
    }

    /**
     * Offline only: there is no WorldEdit player behind it, so identity and association are answered
     * here and every other member is refused rather than guessed at.
     */
    private static final class Handler implements InvocationHandler {

        private final OfflinePlayer offline;
        private final UUID uniqueId;

        private Handler(final OfflinePlayer offline, final UUID uniqueId) {
            this.offline = offline;
            this.uniqueId = uniqueId;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            final Class<?> declaring = method.getDeclaringClass();
            if (declaring == Object.class) {
                return object(method, args);
            }
            if (declaring == UuidSubject.class) {
                return uniqueId;
            }
            return switch (method.getName()) {
                case "getAssociation" -> association(args[0]);
                case "hasGroup" -> Groups.inGroup(uniqueId, (String) args[0]);
                case "hasPermission" -> Boolean.FALSE;
                case "print", "printRaw", "printDebug", "printError", "printInfo" -> null;
                case "getUniqueId" -> uniqueId;
                case "getName", "getDisplayName" -> offline.getName();
                case "isPlayer" -> Boolean.FALSE;
                default -> {
                    CompatDiagnostics.stub("LocalPlayer." + method.getName() + " (offline)");
                    throw new UnsupportedOperationException(
                        "LocalPlayer." + method.getName() + " is not available for an offline player");
                }
            };
        }

        private Object object(final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "equals" -> args[0] instanceof UuidSubject other && uniqueId.equals(other.uwgUuid());
                case "hashCode" -> uniqueId.hashCode();
                default -> "LocalPlayer{" + uniqueId + '}';
            };
        }

        @SuppressWarnings("unchecked")
        private Object association(final Object regions) {
            final List<com.sk89q.worldguard.protection.regions.ProtectedRegion> list =
                (List<com.sk89q.worldguard.protection.regions.ProtectedRegion>) regions;
            boolean member = false;
            for (int i = 0, n = list.size(); i < n; i++) {
                final com.sk89q.worldguard.protection.regions.ProtectedRegion region = list.get(i);
                if (region.isOwner(uniqueId)) {
                    return com.sk89q.worldguard.domains.Association.OWNER;
                }
                if (!member && region.isMember(uniqueId)) {
                    member = true;
                }
            }
            return member
                ? com.sk89q.worldguard.domains.Association.MEMBER
                : com.sk89q.worldguard.domains.Association.NON_MEMBER;
        }
    }
}
