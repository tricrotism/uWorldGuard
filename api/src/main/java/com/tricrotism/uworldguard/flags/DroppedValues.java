package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.logging.Logger;

/**
 * Reporting for entries a set-valued flag could not read back.
 *
 * <p>A set flag keeps the entries it recognizes and drops the rest, which is the right call: one
 * unreadable material should not cost a region the whole list. The danger is doing it quietly. The
 * region is written back from what loaded, so the next save persists the shortened list. On a
 * deny-list the dropped entry is the difference between a material being refused and being allowed,
 * and a material renamed by a Minecraft update is enough to start it.
 */
@NullMarked final class DroppedValues {

    private static final Logger LOG = Logger.getLogger("uWorldGuard");

    private DroppedValues() {}

    static void report(final String flag, final List<String> unreadable) {
        if (unreadable.isEmpty()) {
            return;
        }
        LOG.warning("Flag '" + flag + "' has " + unreadable.size() + " stored value(s) this server"
            + " does not recognise, and they have been dropped: " + String.join(", ", unreadable)
            + ". The next save writes the flag without them. On a deny-list that means those are now"
            + " allowed. Check for a renamed value before saving over it.");
    }
}
