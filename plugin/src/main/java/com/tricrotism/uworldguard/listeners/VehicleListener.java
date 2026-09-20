package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Enforces {@code vehicle-place} and {@code vehicle-destroy}. Boats and minecarts are entities, so
 * neither placing nor breaking one goes through the block-place or block-break flags — without this
 * they stay free to leave and to smash inside an otherwise protected region.
 */
@NullMarked
public final class VehicleListener implements Listener {

    private final RegionQuery query;
    private final MessageService messages;

    public VehicleListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final EntityPlaceEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Vehicle vehicle)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!query.getApplicableRegions(vehicle).testBuild(player.getUniqueId(), Flags.VEHICLE_PLACE)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.VEHICLE_PLACE);
        }
    }

    /**
     * Covers destruction by anything, not just players — a skeleton's arrow or a creeper blast breaks
     * a minecart just as effectively. A player attacker is still resolved so bypass and the denial
     * message work; an attackerless cause simply falls through to the flag.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(final VehicleDestroyEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Vehicle vehicle = event.getVehicle();
        final Player attacker = resolvePlayer(event.getAttacker());
        final boolean allowed = attacker != null
            ? query.getApplicableRegions(vehicle).testBuild(attacker.getUniqueId(), Flags.VEHICLE_DESTROY)
            : query.testState(vehicle, Flags.VEHICLE_DESTROY);
        if (allowed) {
            return;
        }
        if (attacker != null && Bypass.has(attacker)) {
            return;
        }
        event.setCancelled(true);
        if (attacker != null) {
            messages.sendDeny(attacker, Flags.VEHICLE_DESTROY);
        }
    }

    /**
     * Damage short of destruction. A minecart survives several hits, and each one is a
     * {@code VehicleDamageEvent} rather than a {@code VehicleDestroyEvent} — so gating only the final
     * blow would let an outsider whittle a vehicle down and merely fail on the last hit.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final VehicleDamageEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player attacker = resolvePlayer(event.getAttacker());
        if (attacker == null) {
            return;
        }
        if (query.getApplicableRegions(event.getVehicle())
            .testState(Flags.VEHICLE_DESTROY, attacker.getUniqueId())) {
            return;
        }
        if (Bypass.has(attacker)) {
            return;
        }
        event.setCancelled(true);
    }

    private static @Nullable Player resolvePlayer(final @Nullable Entity attacker) {
        if (attacker instanceof Player player) {
            return player;
        }
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
