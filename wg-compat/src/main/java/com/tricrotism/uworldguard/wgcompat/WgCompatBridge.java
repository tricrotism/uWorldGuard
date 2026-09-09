// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.
package com.tricrotism.uworldguard.wgcompat;

import com.tricrotism.uworldguard.region.RegionContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Runtime binding between the {@code com.sk89q.worldguard.*} shim and the uWorldGuard engine.
 * Bound by the plugin on enable (only when WorldEdit is present), unbound first on disable.
 *
 * <p>A single volatile holder record so a reader gets a consistent (container, plugin) pair.
 * Every shim entry point goes through {@link #container()} / {@link #plugin()} so an inactive
 * compat layer fails with a clear {@link IllegalStateException} rather than an NPE or a call
 * into a dead engine.
 */
@NullMarked
public final class WgCompatBridge {

    private record Binding(RegionContainer container, JavaPlugin plugin) {}

    private static volatile @Nullable Binding binding;
    private static volatile String inactiveReason = "uWorldGuard is not enabled";
    private static volatile java.util.function.Predicate<org.bukkit.entity.Player> bypassCheck = player -> false;

    private WgCompatBridge() {
    }

    /**
     * Internal: bound by uWorldGuard on enable. Other plugins must never call this.
     */
    public static void bind(final RegionContainer container, final JavaPlugin plugin) {
        binding = new Binding(container, plugin);
        SessionBridge.rearm();
    }

    /**
     * Internal: records why the compatibility layer was not activated, for {@code /uwg compat}.
     */
    public static void markInactive(final String reason) {
        binding = null;
        inactiveReason = reason;
        SessionDispatch.ACTIVE = false;
    }

    /**
     * Internal: called first in uWorldGuard's disable, so shim callers fail fast during shutdown.
     */
    public static void unbind() {
        markInactive("uWorldGuard is not enabled");
    }

    public static boolean active() {
        return binding != null;
    }

    /**
     * Internal: lets uWorldGuard supply its bypass check, which lives in the plugin module this one
     * cannot depend on. Without it {@code SessionManager.hasBypass} would always answer false.
     */
    public static void bypassCheck(final java.util.function.Predicate<org.bukkit.entity.Player> check) {
        bypassCheck = check;
    }

    public static boolean hasBypass(final org.bukkit.entity.Player player) {
        return bypassCheck.test(player);
    }

    /**
     * Why the layer is inactive. Only meaningful when {@link #active()} is false.
     */
    public static String inactiveReason() {
        return inactiveReason;
    }

    public static RegionContainer container() {
        return require().container;
    }

    public static JavaPlugin plugin() {
        return require().plugin;
    }

    private static Binding require() {
        final Binding b = binding;
        if (b == null) {
            throw new IllegalStateException(
                "uWorldGuard's WorldGuard compatibility layer is inactive"
                    + " (uWorldGuard is disabled, or WorldEdit is not installed)");
        }
        return b;
    }
}
