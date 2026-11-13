// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

/**
 * A string flag, bridging to uWorldGuard's {@code com.tricrotism.uworldguard.flags.StringFlag}.
 */
public class StringFlag extends Flag<String> {

    private final String defaultValue;

    public StringFlag(final String name) {
        this(name, null, null);
    }

    public StringFlag(final String name, final String defaultValue) {
        this(name, null, defaultValue);
    }

    public StringFlag(final String name, final RegionGroup defaultGroup) {
        this(name, defaultGroup, null);
    }

    public StringFlag(final String name, final RegionGroup defaultGroup, final String defaultValue) {
        super(name, defaultGroup);
        this.defaultValue = defaultValue;
    }

    @Override
    public String getDefault() {
        return defaultValue;
    }

    @Override
    public String parseInput(final FlagContext context) throws InvalidFlagFormat {
        return com.tricrotism.uworldguard.flags.StringFlag.unescape(context.getUserInput());
    }

    @Override
    public String unmarshal(final Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Override
    public Object marshal(final String o) {
        return o;
    }
}
