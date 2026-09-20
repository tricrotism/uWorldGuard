// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.Locale;

/**
 * The {@code <flag>-group} qualifier attached to every grouped flag.
 */
public class RegionGroupFlag extends EnumFlag<RegionGroup> {

    private static final String GROUP_SUFFIX = "-group";

    private final RegionGroup def;
    /**
     * The engine flag this qualifies, resolved on first use. Reading a group qualifier off a region
     * happens once per region per flag on every consumer query that uses one, and working the owner
     * out from the name allocates a substring and then looks it up by name. Only a hit is kept: a
     * flag whose plugin has not registered yet resolves again next time, which is what lets a
     * consumer flag start working the moment it registers.
     */
    private volatile com.tricrotism.uworldguard.flags.Flag<?> uwgOwner;

    public RegionGroupFlag(final String name, final RegionGroup def) {
        super(name, RegionGroup.class);
        this.def = def;
    }

    /**
     * Internal: the engine flag this {@code <name>-group} qualifier belongs to, or {@code null} when
     * that flag is not bridged.
     */
    public final com.tricrotism.uworldguard.flags.Flag<?> uwgOwner() {
        final com.tricrotism.uworldguard.flags.Flag<?> cached = uwgOwner;
        if (cached != null) {
            return cached;
        }
        final String name = getName();
        if (name == null || !name.endsWith(GROUP_SUFFIX)) {
            return null;
        }
        final com.tricrotism.uworldguard.flags.Flag<?> resolved =
            com.tricrotism.uworldguard.flags.WgFlagNames.resolve(
                name.substring(0, name.length() - GROUP_SUFFIX.length()));
        if (resolved != null) {
            uwgOwner = resolved;
        }
        return resolved;
    }

    @Override
    public RegionGroup getDefault() {
        return def;
    }

    /**
     * Whether a group-qualified flag value applies to {@code player} across a whole region set. A
     * {@code null} group means {@link RegionGroup#ALL}.
     */
    public static boolean isMember(
        final ApplicableRegionSet set, final RegionGroup group, final LocalPlayer player
    ) {
        return switch (group == null ? RegionGroup.ALL : group) {
            case ALL -> true;
            case NONE -> false;
            case MEMBERS -> set.isMemberOfAll(player);
            case OWNERS -> set.isOwnerOfAll(player);
            case NON_MEMBERS -> !set.isMemberOfAll(player);
            case NON_OWNERS -> !set.isOwnerOfAll(player);
        };
    }

    /**
     * Whether a group-qualified flag value applies to {@code player} on one region. A {@code null}
     * player is treated as a non-member.
     */
    public static boolean isMember(
        final ProtectedRegion region, final RegionGroup group, final LocalPlayer player
    ) {
        final RegionGroup resolved = group == null ? RegionGroup.ALL : group;
        if (resolved == RegionGroup.ALL) {
            return true;
        }
        if (resolved == RegionGroup.NONE) {
            return false;
        }
        if (player == null) {
            return resolved == RegionGroup.NON_MEMBERS || resolved == RegionGroup.NON_OWNERS;
        }
        return switch (resolved) {
            case MEMBERS -> region.isMember(player);
            case OWNERS -> region.isOwner(player);
            case NON_MEMBERS -> !region.isMember(player);
            case NON_OWNERS -> !region.isOwner(player);
            default -> true;
        };
    }

    @Override
    public RegionGroup detectValue(final String input) {
        if (input == null) {
            return null;
        }
        return switch (input.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "all", "everyone" -> RegionGroup.ALL;
            case "members", "member" -> RegionGroup.MEMBERS;
            case "owners", "owner" -> RegionGroup.OWNERS;
            case "nonmembers", "non_members", "nonmember", "non_member" -> RegionGroup.NON_MEMBERS;
            case "nonowners", "non_owners", "nonowner", "non_owner" -> RegionGroup.NON_OWNERS;
            case "none" -> RegionGroup.NONE;
            default -> null;
        };
    }
}
