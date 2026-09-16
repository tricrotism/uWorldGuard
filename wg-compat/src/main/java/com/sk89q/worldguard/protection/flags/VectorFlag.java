// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.math.Vector3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flag holding a WorldEdit {@link Vector3}. Marshals to an {@code x}/{@code y}/{@code z} map, and
 * reads back either that or a comma-separated triple.
 *
 * <p>WorldEdit references stay inside method bodies, so the class loads on a server without it.
 */
public class VectorFlag extends Flag<Vector3> {

    public VectorFlag(final String name) {
        super(name);
    }

    public VectorFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public Vector3 parseInput(final FlagContext context) throws InvalidFlagFormat {
        final Vector3 value = fromString(context.getUserInput());
        if (value == null) {
            throw new InvalidFlagFormat("Expected 'x,y,z' for " + getName() + " but got '"
                + context.getUserInput() + "'");
        }
        return value;
    }

    @Override
    public Vector3 unmarshal(final Object o) {
        if (o instanceof Map<?, ?> map) {
            final Double x = number(map.get("x"));
            final Double y = number(map.get("y"));
            final Double z = number(map.get("z"));
            return x == null || y == null || z == null ? null : Vector3.at(x, y, z);
        }
        return o == null ? null : fromString(String.valueOf(o));
    }

    @Override
    public Object marshal(final Vector3 o) {
        if (o == null) {
            return null;
        }
        final Map<String, Object> map = new LinkedHashMap<>(4);
        map.put("x", o.x());
        map.put("y", o.y());
        map.put("z", o.z());
        return map;
    }

    private static Vector3 fromString(final String input) {
        final String[] parts = input.trim().split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return Vector3.at(
                Double.parseDouble(parts[0].trim()),
                Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Double number(final Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
