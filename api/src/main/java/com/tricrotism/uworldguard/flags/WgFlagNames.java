package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WorldGuard flag names that uWorldGuard implements under a different name — the single
 * source of truth shared by the migration importer and the WorldGuard API compatibility
 * layer. Only exact behavioral equivalents belong here; a flag whose uWorldGuard
 * counterpart is broader or narrower is deliberately left unmapped rather than silently
 * widened.
 *
 * <p>One WorldGuard flag can cover ground uWorldGuard splits in two. Such a flag names its main
 * counterpart in the alias table and the rest in {@link #COMPANIONS}. The importer writes the
 * stored value to every one of them, so migrating loses nothing. The runtime API binds the shim to
 * the main counterpart alone, because one flag cannot delegate to two.
 */
@NullMarked
public final class WgFlagNames {

    private static final Map<String, String> WG_TO_UWG = Map.of(
        "block-trampling", "crop-trample",
        "wind-charge-burst", "wind-charge",
        "frosted-ice-form", "frostwalker",
        "min-heal", "heal-min-health",
        "max-heal", "heal-max-health",
        "feed-min-hunger", "min-food",
        "feed-max-hunger", "max-food",
        "spawn", "respawn-location"
    );

    /**
     * The uWorldGuard flags a WorldGuard flag also covers, beyond the one the alias table names.
     * WorldGuard's {@code block-trampling} governs farmland and eggs together. uWorldGuard keeps
     * them apart so a region can protect one without the other.
     */
    private static final Map<String, List<String>> COMPANIONS = Map.of(
        "block-trampling", List.of("egg-trample")
    );

    private WgFlagNames() {
    }

    /**
     * The flags a stored WorldGuard value has to be written to as well as {@link #resolve}'s, so a
     * migrated region keeps everything the WorldGuard flag was protecting. Empty for almost every
     * name.
     */
    public static List<Flag<?>> companions(final String wgName) {
        final List<String> names = COMPANIONS.get(wgName.toLowerCase(Locale.ROOT));
        if (names == null) {
            return List.of();
        }
        final List<Flag<?>> flags = new ArrayList<>(names.size());
        for (final String name : names) {
            final Flag<?> flag = Flags.get(name);
            if (flag != null) {
                flags.add(flag);
            }
        }
        return flags;
    }

    /**
     * The uWorldGuard name for a WorldGuard-only spelling, or {@code null} if the names match
     * (or no equivalent exists).
     */
    public static @Nullable String uwgName(final String wgName) {
        return WG_TO_UWG.get(wgName.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolve a WorldGuard flag name to the registered flag: directly, then through the alias
     * table. {@code null} when uWorldGuard has no equivalent.
     */
    public static @Nullable Flag<?> resolve(final String wgName) {
        final Flag<?> direct = Flags.get(wgName);
        if (direct != null) {
            return direct;
        }
        final String alias = WG_TO_UWG.get(wgName.toLowerCase(Locale.ROOT));
        return alias == null ? null : Flags.get(alias);
    }
}
