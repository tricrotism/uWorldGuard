// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import java.util.UUID;

/**
 * A flag holding one player UUID.
 */
public class UUIDFlag extends Flag<UUID> {

    public UUIDFlag(final String name) {
        super(name);
    }

    public UUIDFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public UUID parseInput(final FlagContext context) throws InvalidFlagFormat {
        final UUID value = parse(context.getUserInput());
        if (value == null) {
            throw new InvalidFlagFormat("Expected a UUID for " + getName() + " but got '"
                + context.getUserInput() + "'");
        }
        return value;
    }

    @Override
    public UUID unmarshal(final Object o) {
        if (o instanceof UUID uuid) {
            return uuid;
        }
        return o == null ? null : parse(String.valueOf(o));
    }

    @Override
    public Object marshal(final UUID o) {
        return o == null ? null : o.toString();
    }

    /**
     * Unparseable input is {@code null} rather than an exception, because this also runs on stored
     * values: a hand-edited region file should cost one flag, not the world's whole load.
     */
    private static UUID parse(final String input) {
        try {
            return UUID.fromString(input.trim());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
