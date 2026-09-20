// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.managers.RegionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point to every world's regions, backed by uWorldGuard's
 * {@code com.tricrotism.uworldguard.region.RegionContainer}.
 *
 * <p>uWorldGuard owns loading, saving and migration, so this carries only the read surface consumers
 * actually use: {@link #get}, {@link #getLoaded()} and {@link #createQuery()}. The lifecycle methods
 * are no-ops rather than stubs, because the container is always initialised while the compat layer
 * is active.
 *
 * <p>Kept abstract to match WorldGuard, where consumers only ever receive an instance from the
 * platform; {@code com.tricrotism.uworldguard.wgcompat.CompatRegionContainer} is the one the shim
 * hands out.
 */
public abstract class RegionContainer {

    /**
     * The manager for a world, or {@code null} while that world's regions are still loading.
     */
    public RegionManager get(final com.sk89q.worldedit.world.World world) {
        final org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (bukkitWorld == null) {
            return null;
        }
        return get(bukkitWorld);
    }

    public List<RegionManager> getLoaded() {
        final List<org.bukkit.World> worlds = org.bukkit.Bukkit.getWorlds();
        final List<RegionManager> loaded = new ArrayList<>(worlds.size());
        for (int i = 0, n = worlds.size(); i < n; i++) {
            final RegionManager manager = get(worlds.get(i));
            if (manager != null) {
                loaded.add(manager);
            }
        }
        return loaded;
    }

    public RegionQuery createQuery() {
        return RegionQuery.UWG_SHARED;
    }

    /**
     * No-op: uWorldGuard initialises its container on enable.
     */
    public void initialize() {
    }

    /**
     * No-op: reloading regions is uWorldGuard's own command.
     */
    public void reload() {
    }

    /**
     * No-op: uWorldGuard unloads a world's regions with the world.
     */
    public void unload() {
    }

    /**
     * @see #unload()
     */
    public void unload(final com.sk89q.worldedit.world.World world) {
    }

    private RegionManager get(final org.bukkit.World world) {
        final com.tricrotism.uworldguard.region.RegionManager manager =
            com.tricrotism.uworldguard.wgcompat.WgCompatBridge.container().get(world);
        return manager == null ? null
            : com.tricrotism.uworldguard.wgcompat.RegionAdapters.manager(manager, world.getName());
    }
}
