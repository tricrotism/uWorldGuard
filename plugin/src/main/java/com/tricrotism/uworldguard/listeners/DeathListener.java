package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.util.Locations;
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

    public DeathListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        final Location location = event.getEntity().getLocation();
        if (Boolean.TRUE.equals(query.queryValue(location, Flags.KEEP_INVENTORY))) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        if (Boolean.TRUE.equals(query.queryValue(location, Flags.KEEP_EXP))) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        final String value = query.queryValue(player.getLocation(), Flags.RESPAWN_LOCATION);
        if (value == null) {
            return;
        }
        final Location target = Locations.parse(value);
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }
}
