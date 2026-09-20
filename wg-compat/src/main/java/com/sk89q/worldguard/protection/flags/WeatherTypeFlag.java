// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;

import java.util.Locale;

/**
 * A flag holding one WorldEdit weather type.
 *
 * <p>WorldEdit references stay inside method bodies, so the class loads on a server without it.
 */
public class WeatherTypeFlag extends Flag<WeatherType> {

    public WeatherTypeFlag(final String name) {
        super(name);
    }

    public WeatherTypeFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public WeatherType parseInput(final FlagContext context) throws InvalidFlagFormat {
        final WeatherType value = lookup(context.getUserInput());
        if (value == null) {
            throw new InvalidFlagFormat("Unknown weather type '" + context.getUserInput()
                + "' for " + getName());
        }
        return value;
    }

    @Override
    public WeatherType unmarshal(final Object o) {
        return o == null ? null : lookup(String.valueOf(o));
    }

    @Override
    public Object marshal(final WeatherType o) {
        return o == null ? null : o.id();
    }

    private static WeatherType lookup(final String input) {
        return WeatherTypes.get(input.trim().toLowerCase(Locale.ROOT));
    }
}
