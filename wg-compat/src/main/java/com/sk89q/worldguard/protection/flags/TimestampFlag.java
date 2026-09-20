// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * A flag holding an instant, stored as ISO-8601.
 */
public class TimestampFlag extends Flag<Instant> {

    public TimestampFlag(final String name) {
        super(name);
    }

    public TimestampFlag(final String name, final RegionGroup defaultGroup) {
        super(name, defaultGroup);
    }

    @Override
    public Instant parseInput(final FlagContext context) throws InvalidFlagFormat {
        final String input = context.getUserInput().trim();
        if ("now".equalsIgnoreCase(input)) {
            return Instant.now();
        }
        final Instant value = parse(input);
        if (value == null) {
            throw new InvalidFlagFormat("Expected an ISO-8601 timestamp or 'now' for " + getName()
                + " but got '" + input + "'");
        }
        return value;
    }

    @Override
    public Instant unmarshal(final Object o) {
        if (o instanceof Instant instant) {
            return instant;
        }
        if (o instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        return o == null ? null : parse(String.valueOf(o));
    }

    @Override
    public Object marshal(final Instant o) {
        return o == null ? null : o.toString();
    }

    private static Instant parse(final String input) {
        try {
            return Instant.parse(input);
        } catch (final DateTimeParseException e) {
            return null;
        }
    }
}
