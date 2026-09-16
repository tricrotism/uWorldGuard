// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldedit.world.gamemode.GameModes;

import java.util.Locale;

/**
 * A flag holding one WorldEdit game mode.
 *
 * <p>WorldEdit references stay inside method bodies, so the class loads on a server without it.
 */
public class GameModeTypeFlag extends Flag<GameMode> {

    public GameModeTypeFlag(final String name) {
        super(name);
    }

    public GameModeTypeFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public GameMode parseInput(final FlagContext context) throws InvalidFlagFormat {
        final GameMode value = lookup(context.getUserInput());
        if (value == null) {
            throw new InvalidFlagFormat("Unknown game mode '" + context.getUserInput()
                + "' for " + getName());
        }
        return value;
    }

    @Override
    public GameMode unmarshal(final Object o) {
        return o == null ? null : lookup(String.valueOf(o));
    }

    @Override
    public Object marshal(final GameMode o) {
        return o == null ? null : o.id();
    }

    private static GameMode lookup(final String input) {
        return GameModes.get(input.trim().toLowerCase(Locale.ROOT));
    }
}
