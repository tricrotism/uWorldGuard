// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;

/**
 * A {@link Location} that remembers the name of the world it was stored against.
 *
 * <p>A region flag outlives any particular world object: a location saved for a world that is not
 * loaded yet has nothing to point at, and dropping the value would silently lose a teleport target
 * over a load-order accident. Keeping the name lets the value survive and resolve later.
 */
public class LazyLocation extends Location {

    private final String worldName;

    public LazyLocation(final String worldName, final Vector3 position) {
        super(resolve(worldName), position);
        this.worldName = worldName;
    }

    public LazyLocation(final String worldName, final Vector3 position, final float yaw, final float pitch) {
        super(resolve(worldName), position, yaw, pitch);
        this.worldName = worldName;
    }

    public String getWorldName() {
        return worldName;
    }

    public LazyLocation setAngles(final float yaw, final float pitch) {
        return new LazyLocation(worldName, toVector(), yaw, pitch);
    }

    public LazyLocation setPosition(final Vector3 position) {
        return new LazyLocation(worldName, position, getYaw(), getPitch());
    }

    /**
     * The world by that name, or WorldEdit's null world when it is not loaded. The null world is what
     * keeps the {@link Location} superclass constructible without one, which is the whole point.
     */
    private static World resolve(final String worldName) {
        final World world = LocationCodec.worldByName(worldName);
        return world == null ? com.sk89q.worldedit.world.NullWorld.getInstance() : world;
    }

    @Override
    public String toString() {
        return "LazyLocation{" + worldName + " " + toVector() + "}";
    }
}
