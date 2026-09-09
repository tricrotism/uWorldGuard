// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard;

import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;

import java.util.logging.Logger;

/**
 * WorldGuard's singleton entry point: {@code WorldGuard.getInstance().getPlatform()}.
 *
 * <p>The platform is created on first use rather than installed by a bootstrap, so a consumer that
 * only ever calls {@code getInstance()} works whether or not uWorldGuard has finished enabling.
 *
 * <p>Nothing in this class resolves a WorldEdit type from a static or instance initialiser — it sits
 * on the same load path as {@link com.sk89q.worldguard.bukkit.WorldGuardPlugin}, which must stay
 * loadable on a server with no WorldEdit installed.
 *
 * <p>{@code getProfileCache()}, {@code getProfileService()}, {@code getSupervisor()},
 * {@code getExecutorService()}, {@code getExceptionConverter()} and {@code checkPlayer(Actor)} are
 * not shipped: each returns a type this layer does not provide.
 */
public final class WorldGuard {

    public static final Logger logger = Logger.getLogger("uWorldGuard");

    private static final WorldGuard INSTANCE = new WorldGuard();

    private static final String API_VERSION = com.tricrotism.uworldguard.util.WorldGuardApiLevel.VERSION;

    private volatile WorldGuardPlatform platform;
    private volatile FlagRegistry flagRegistry;

    private WorldGuard() {
    }

    public static WorldGuard getInstance() {
        return INSTANCE;
    }

    /**
     * The WorldGuard API level this shim emulates, not uWorldGuard's own version — consumers compare
     * it against WorldGuard releases. {@code getPlatform().getPlatformVersion()} gives uWorldGuard's.
     */
    public static String getVersion() {
        return API_VERSION;
    }

    public WorldGuardPlatform getPlatform() {
        WorldGuardPlatform current = platform;
        if (current == null) {
            synchronized (this) {
                current = platform;
                if (current == null) {
                    current = com.tricrotism.uworldguard.wgcompat.UwgPlatform.INSTANCE;
                    platform = current;
                }
            }
        }
        return current;
    }

    public void setPlatform(final WorldGuardPlatform platform) {
        this.platform = platform;
    }

    public FlagRegistry getFlagRegistry() {
        FlagRegistry current = flagRegistry;
        if (current == null) {
            synchronized (this) {
                current = flagRegistry;
                if (current == null) {
                    final SimpleFlagRegistry registry = new SimpleFlagRegistry();
                    registry.registerAll(Flags.uwgAll());
                    current = registry;
                    flagRegistry = current;
                }
            }
        }
        return current;
    }

    /**
     * Idempotent: creates the platform if nothing has asked for it yet. uWorldGuard drives its own
     * lifecycle, so there is nothing else to do here.
     */
    public void setup() {
        getPlatform();
    }

    public void disable() {
        final WorldGuardPlatform current = platform;
        if (current != null) {
            current.unload();
        }
        platform = null;
    }
}
