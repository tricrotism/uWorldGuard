package com.tricrotism.uworldguard.util;

import org.jspecify.annotations.NullMarked;

/**
 * The WorldGuard API level this build implements.
 *
 * <p>Lives here because two unrelated places have to agree on it: what
 * {@code com.sk89q.worldguard.WorldGuard.getVersion()} answers, and the version uWorldGuard
 * publishes to plugins that gate their hook on a WorldGuard version number. It is deliberately not
 * uWorldGuard's own version, which is a 1.x number and fails every such check.
 */
@NullMarked
public final class WorldGuardApiLevel {

    public static final String VERSION = "7.0.18";

    private WorldGuardApiLevel() {}
}
