package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.service.ChamberedPearlTracker;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NullMarked;

/**
 * Feeds the {@link ChamberedPearlTracker}: records a player's ender pearls on launch and drops them
 * from tracking when they land. Region entry handling (the actual removal) lives in MovementListener.
 *
 * <p>Tracking a pearl costs a keyed set, a scheduled expiry and a strong reference to the entity for
 * twenty seconds, so nothing is tracked unless some region actually sets the flag that reads it.
 */
@NullMarked
public final class PearlListener implements Listener {

    private final RegionContainerImpl container;
    private final ChamberedPearlTracker tracker;

    public PearlListener(final RegionContainerImpl container, final ChamberedPearlTracker tracker) {
        this.container = container;
        this.tracker = tracker;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(final ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)
            || !container.anyRegionUses(Flags.CHAMBERED_ENDERPEARL)) {
            return;
        }
        final ProjectileSource shooter = pearl.getShooter();
        if (shooter instanceof Player player) {
            tracker.track(player.getUniqueId(), pearl);
        }
    }

    @EventHandler
    public void onHit(final ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        if (pearl.getShooter() instanceof Player player) {
            tracker.untrack(player.getUniqueId(), pearl);
        }
    }
}
