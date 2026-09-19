// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import java.util.Collection;

/**
 * A typed region flag. Every shim flag carries an opaque binding to the uWorldGuard engine flag it
 * delegates to; see {@code com.tricrotism.uworldguard.wgcompat.FlagBridge}.
 *
 * @param <T> the value type
 */
public abstract class Flag<T> {

    private final String name;
    private final RegionGroupFlag regionGroupFlag;

    /**
     * Internal bridge state. Not part of the WorldGuard API — never touch this from consumer code.
     *
     * <p>Volatile because a consumer's own flag is bound on whichever thread registers it, usually
     * during that plugin's enable, and read afterwards from every region thread. The built-in flags
     * are published by the {@code bound} flag in {@code FlagBridge}; a flag registered after that has
     * no such barrier, and a region thread that saw the field unset read the flag as unbridged and
     * resolved it as unset.
     */
    private volatile Object uwgBinding;

    protected Flag(final String name) {
        this(name, null);
    }

    protected Flag(final String name, final RegionGroup defaultGroup) {
        if (name != null && !isValidName(name)) {
            throw new IllegalArgumentException("Invalid flag name: " + name);
        }
        this.name = name;
        this.regionGroupFlag = defaultGroup == null || this instanceof RegionGroupFlag
            ? null
            : new RegionGroupFlag(name + "-group", defaultGroup);
    }

    public final String getName() {
        return name;
    }

    public final RegionGroupFlag getRegionGroupFlag() {
        return regionGroupFlag;
    }

    /**
     * Internal: attach the engine binding. Not part of the WorldGuard API.
     */
    public final void uwgBind(final Object binding) {
        this.uwgBinding = binding;
    }

    /**
     * Internal: the attached engine binding, or {@code null} when this flag is not bridged.
     * Not part of the WorldGuard API.
     */
    public final Object uwgBinding() {
        return uwgBinding;
    }

    public T getDefault() {
        return null;
    }

    public T chooseValue(final Collection<T> values) {
        return null;
    }

    public boolean hasConflictStrategy() {
        return false;
    }

    public boolean implicitlySetWithMembership() {
        return false;
    }

    public boolean requiresSubject() {
        return false;
    }

    public boolean usesMembershipAsDefault() {
        return false;
    }

    public abstract T parseInput(FlagContext context) throws InvalidFlagFormat;

    public abstract T unmarshal(Object o);

    public abstract Object marshal(T o);

    public static boolean isValidName(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + name + "}";
    }
}
