// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

/**
 * A flag holding a command to run. Stored with a leading slash, which is the difference from a plain
 * {@link StringFlag}: an operator types {@code heal} or {@code /heal} and both have to end up
 * dispatchable.
 */
public class CommandStringFlag extends Flag<String> {

    public CommandStringFlag(final String name) {
        super(name);
    }

    public CommandStringFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public String parseInput(final FlagContext context) throws InvalidFlagFormat {
        final String input = context.getUserInput().trim();
        if (input.isEmpty()) {
            throw new InvalidFlagFormat("Expected a command for " + getName());
        }
        return normalise(input);
    }

    @Override
    public String unmarshal(final Object o) {
        return o == null ? null : normalise(String.valueOf(o));
    }

    @Override
    public Object marshal(final String o) {
        return o;
    }

    private static String normalise(final String command) {
        return command.startsWith("/") ? command : "/" + command;
    }
}
