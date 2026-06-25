package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Enforces the glide (elytra) and nether-portals flags.
 */
@NullMarked
public final class TravelListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;

    public TravelListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(final EntityToggleGlideEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!query.testState(player, Flags.GLIDE)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!query.testState(event.getFrom(), Flags.NETHER_PORTALS)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehiclePortal(final EntityPortalEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = ridingPlayer(event.getEntity());
        if (player == null) {
            return;
        }
        if (!query.testState(event.getFrom(), Flags.NETHER_PORTALS)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    private static @Nullable Player ridingPlayer(final Entity vehicle) {
        for (final Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
