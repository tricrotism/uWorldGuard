package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.event.RegionChangeEvent;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NullMarked;

/**
 * The one place a region edit asks other plugins for permission. Commands and menus both edit
 * regions, and a veto enforced on one surface and not the other is no veto: a plugin refusing a flag
 * change from {@code /uwg flag} was bypassed by clicking the same flag in {@code /uwg menu}.
 */
@NullMarked
public final class RegionEdits {

    private RegionEdits() {}

    public static final String CONSOLE_FLAG_NODE = "uworldguard.region.flag.console";

    /**
     * Whether {@code sender} may set {@code flag} at all. The console-command flags run their value
     * as the console, so the general flag node is not enough for them. Checked by the command and the
     * menu, the two surfaces a player edits flags from.
     */
    public static boolean mayEditFlag(final CommandSender sender, final Flag<?> flag) {
        if (flag != Flags.CONSOLE_COMMAND_ON_ENTRY && flag != Flags.CONSOLE_COMMAND_ON_EXIT
            || sender.hasPermission(CONSOLE_FLAG_NODE)) {
            return true;
        }
        sender.sendMessage(Messages.format("<red>Setting <aqua><flag></aqua> needs <white><node></white>.",
            Placeholder.unparsed("flag", flag.getName()), Placeholder.unparsed("node", CONSOLE_FLAG_NODE)));
        return false;
    }

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
