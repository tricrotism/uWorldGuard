package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.event.RegionChangeEvent;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NullMarked;

/**
 * The one place a region edit asks other plugins for permission. Commands and menus both edit
 * regions, and a veto enforced on one surface and not the other is no veto: a plugin refusing a flag
 * change from {@code /uwg flag} was bypassed by clicking the same flag in {@code /uwg menu}.
 */
@NullMarked
public final class RegionEdits {

    private RegionEdits() {}

    /**
     * Fires {@code event} and tells its actor when it was refused. Returns true when the edit must
     * not go ahead, so every call site reads {@code if (vetoed(...)) return;} immediately before
     * the write.
     */
    public static boolean vetoed(final RegionChangeEvent event) {
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            return false;
        }
        if (event.getActor() != null) {
            final Component reason = event.getCancelMessage();
            event.getActor().sendMessage(reason != null
                ? reason
                : Messages.format("<red>Another plugin refused that change."));
        }
        return true;
    }
}
