// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.entity.EntityTypes;

/**
 * A flag holding one WorldEdit entity type, stored by its namespaced id.
 *
 * <p>WorldEdit references stay inside method bodies, so the class loads on a server without it.
 */
public class EntityTypeFlag extends Flag<EntityType> {

    public EntityTypeFlag(final String name) {
        super(name);
    }

    public EntityTypeFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public EntityType parseInput(final FlagContext context) throws InvalidFlagFormat {
        final EntityType value = lookup(context.getUserInput());
        if (value == null) {
            throw new InvalidFlagFormat("Unknown entity type '" + context.getUserInput()
                + "' for " + getName());
        }
        return value;
    }

    @Override
    public EntityType unmarshal(final Object o) {
        return o == null ? null : lookup(String.valueOf(o));
    }

    @Override
    public Object marshal(final EntityType o) {
        return o == null ? null : o.id();
    }

    /**
     * Accepts {@code zombie} as readily as {@code minecraft:zombie}, since the registry is keyed by
     * the namespaced form and an operator types the short one.
     */
    private static EntityType lookup(final String input) {
        final String id = input.trim().toLowerCase(java.util.Locale.ROOT);
        final EntityType direct = EntityTypes.get(id);
        return direct != null || id.indexOf(':') >= 0 ? direct : EntityTypes.get("minecraft:" + id);
    }
}
