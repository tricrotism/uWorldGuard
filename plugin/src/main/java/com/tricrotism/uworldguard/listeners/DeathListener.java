package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.Locations;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Enforces keep-inventory / keep-exp on death and respawn-location on respawn.
 */
@NullMarked
public final class DeathListener implements Listener {

    private final RegionQuery query;
    private final MessageService messages;

    public DeathListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player victim = event.getEntity();
        final ApplicableRegionSet set = query.getApplicableRegions(victim);
        if (Boolean.TRUE.equals(set.queryValue(Flags.KEEP_INVENTORY, victim.getUniqueId()))) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        if (Boolean.TRUE.equals(set.queryValue(Flags.KEEP_EXP, victim.getUniqueId()))) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final String value = query.queryValue(player, Flags.RESPAWN_LOCATION);
        if (value != null) {
            final Location target = Locations.parse(messages.expand(player, value));
            if (target != null) {
                event.setRespawnLocation(target);
            }
        }

        if (SessionDispatch.ACTIVE) {
            SessionDispatch.testMove(player, player.getLocation(), event.getRespawnLocation(),
                SessionDispatch.Move.RESPAWN);
        }
    }
}
