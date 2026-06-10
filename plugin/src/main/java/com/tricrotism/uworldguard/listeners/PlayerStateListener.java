package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Enforces sleep, enderpearl/chorus teleport, chest-access, ride, invincible/godmode, and
 * item-durability flags.
 */
@NullMarked
public final class PlayerStateListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;

    public PlayerStateListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSleep(final PlayerBedEnterEvent event) {
        if (event.getPlayer().hasPermission(BYPASS)) {
            return;
        }
        if (!query.testState(event.getBed().getLocation(), Flags.SLEEP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        final var flag = switch (event.getCause().name()) {
            case "ENDER_PEARL" -> Flags.ENDERPEARL;
            case "CHORUS_FRUIT" -> Flags.CHORUS_TELEPORT;
            default -> null;
        };
        if (flag != null && !event.getPlayer().hasPermission(BYPASS)
            && !query.testState(event.getTo(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestAccess(final InventoryOpenEvent event) {
        final Location location = event.getInventory().getLocation();
        if (location == null || !(event.getPlayer() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(location);
        if (!set.testState(Flags.CHEST_ACCESS) && !set.canBuild(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRide(final VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getVehicle().getLocation());
        if (!set.testState(Flags.RIDE) && !set.canBuild(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvincible(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(player.getLocation());
        if (Boolean.TRUE.equals(set.queryValue(Flags.INVINCIBLE))
            || Boolean.TRUE.equals(set.queryValue(Flags.GODMODE))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(final PlayerItemDamageEvent event) {
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        if (!query.testState(player.getLocation(), Flags.ITEM_DURABILITY)) {
            event.setCancelled(true);
        }
    }
}
