package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jspecify.annotations.NullMarked;

import java.util.Locale;
import java.util.Set;

/**
 * Enforces the blocked-cmds / allowed-cmds flags: which commands a player may run while standing in a
 * region. blocked-cmds is a deny-list unioned across the whole region stack, so any region that
 * blocks a command wins. allowed-cmds, when set, is exclusive — the highest-priority region that
 * defines it decides, and anything it does not name is refused.
 *
 * <p>Namespaced forms are matched too: {@code /minecraft:tp} and {@code /essentials:home} resolve to
 * {@code tp} and {@code home}, so a list cannot be sidestepped by qualifying the command. Both the
 * qualified and bare forms are tested, so listing either one works.
 */
@NullMarked
public final class CommandListener implements Listener {

    private final RegionQuery query;
    private final MessageService messages;

    public CommandListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(player);
        final boolean hasBlocked = set.worldUses(Flags.BLOCKED_CMDS);
        final boolean hasAllowed = set.worldUses(Flags.ALLOWED_CMDS);
        if (!hasBlocked && !hasAllowed) {
            return;
        }
        if (Bypass.has(player)) {
            return;
        }

        final String qualified = root(event.getMessage());
        if (qualified.isEmpty()) {
            return;
        }
        final int colon = qualified.indexOf(':');
        final String bare = colon < 0 ? qualified : qualified.substring(colon + 1);

        if (hasBlocked
            && (set.flagSetContains(Flags.BLOCKED_CMDS, qualified, player.getUniqueId())
            || colon >= 0 && set.flagSetContains(Flags.BLOCKED_CMDS, bare, player.getUniqueId()))) {
            event.setCancelled(true);
            messages.sendDeny(player, Flags.BLOCKED_CMDS);
            return;
        }

        if (hasAllowed) {
            final Set<String> allowed = set.queryValue(Flags.ALLOWED_CMDS, player.getUniqueId());
            if (allowed != null && !allowed.contains(qualified) && (colon < 0 || !allowed.contains(bare))) {
                event.setCancelled(true);
                messages.sendDeny(player, Flags.BLOCKED_CMDS);
            }
        }
    }

    /**
     * The command word of a raw chat line: {@code "/Home foo bar"} to {@code "home"}. Lower-cased to
     * match the normalisation {@code StringSetFlag} applies to stored values.
     */
    private static String root(final String message) {
        int start = 0;
        if (start < message.length() && message.charAt(start) == '/') {
            start++;
        }
        int end = message.indexOf(' ', start);
        if (end < 0) {
            end = message.length();
        }
        return message.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
