package com.tricrotism.uworldguard.region;

import org.jspecify.annotations.NullMarked;

/**
 * Whether a region's owner and member lists grant anything.
 *
 * <p>On by default, which is the behaviour every WorldGuard-shaped setup expects: owners and members
 * are trusted inside their region and flags restrict everyone else. Turned off, protection becomes
 * purely flag-driven. Trust lists are still stored and still editable, they simply stop being
 * consulted, so a region with no {@code build} flag denies everybody, its own owner included.
 *
 * <p>The switch lives in {@link ProtectedRegion#isOwner} and {@link ProtectedRegion#isMember} rather
 * than at the two dozen places that ask them. Those two methods are what every decision in the
 * plugin, the WorldGuard compatibility layer and any consumer plugin ultimately goes through, so
 * gating them is the only version of this that cannot drift out of step with a caller somebody
 * forgot. The cost is one volatile read on a path that was already walking a parent chain.
 *
 * <p>Lives here rather than in the plugin's settings because {@code wg-compat} and the API answer
 * the same question and cannot see the plugin's configuration. Set from {@code Settings.load}, so a
 * reload applies it.
 */
@NullMarked
public final class Membership {

    private static volatile boolean grantsTrust = true;

    private Membership() {}

    /**
     * Whether owner and member lists are consulted at all.
     */
    public static boolean grantsTrust() {
        return grantsTrust;
    }

    public static void set(final boolean value) {
        grantsTrust = value;
    }
}
