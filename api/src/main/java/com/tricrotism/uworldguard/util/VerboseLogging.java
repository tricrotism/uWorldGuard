package com.tricrotism.uworldguard.util;

import org.jspecify.annotations.NullMarked;

/**
 * Whether uWorldGuard prints its advisory diagnostics: flag-registration conflicts, calls into
 * stubbed compatibility members, per-world notes about stored-but-unenforced settings. None of
 * these change behavior. They explain behavior that already happened, and on a server with a
 * WorldGuard-era plugin set they can run to a hundred lines a boot. Off by default.
 *
 * <p>Lives here because both the plugin and the {@code wg-compat} shim log under it, and the shim
 * cannot see the plugin's configuration. Set from {@code Settings.load}, so a reload applies it.
 */
@NullMarked
public final class VerboseLogging {

    private static volatile boolean enabled;

    private VerboseLogging() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void set(final boolean value) {
        enabled = value;
    }
}
