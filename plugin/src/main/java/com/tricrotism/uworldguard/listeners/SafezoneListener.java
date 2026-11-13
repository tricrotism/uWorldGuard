package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * {@code regions.safezone-isolation}: a player standing where {@code pvp} is denied to them cannot
 * build in, use, hurt or shoot into a region that explicitly allows {@code pvp}, so a safezone is not
 * a protected spot to fight from. Only registered when the setting is on.
 */
@NullMarked
public final class SafezoneListener implements Listener {

    private final RegionQuery query;
    private final MessageService messages;

    public SafezoneListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        refuse(event, event.getPlayer(), event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        refuse(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        refuse(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        refuse(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block != null && event.getAction() != Action.PHYSICAL) {
            refuse(event, event.getPlayer(), block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceEntity(final EntityPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null) {
            refuse(event, player, event.getEntity(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceHanging(final HangingPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null) {
            refuse(event, player, event.getEntity(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakHanging(final HangingBreakByEntityEvent event) {
        final Player player = Attackers.of(event.getRemover());
        if (player != null) {
            refuse(event, player, event.getEntity(), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageVehicle(final VehicleDamageEvent event) {
        final Player player = Attackers.of(event.getAttacker());
        if (player != null) {
            refuse(event, player, event.getVehicle(), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        refuse(event, event.getPlayer(), event.getRightClicked(), true);
    }

    /**
     * Any hit, melee or ranged, on a mob, animal or stand. A hit on a player is left to the pvp
     * check, which already refuses an attacker standing where pvp is denied.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        final Entity victim = event.getEntity();
        if (victim instanceof Player || !query.usesFlag(victim.getWorld(), Flags.PVP)
            || EventGate.disabled(event)) {
            return;
        }
        final Player attacker = Attackers.of(event.getDamager());
        if (attacker != null && !attacker.equals(victim) && isolated(attacker, query.getApplicableRegions(victim))) {
            event.setCancelled(true);
        }
    }

    /**
     * A shot from the safezone landing in the pvp region is cancelled and the projectile removed, so a
     * wind charge never bursts and an arrow does not carry on through the block it hit. Pearls are left
     * alone, since landing in the pvp region only exposes the thrower, and tridents are not removed
     * because that would delete the player's item. A trident's hit on an entity is still refused by
     * {@link #onDamage}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(final ProjectileHitEvent event) {
        final Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)
            || projectile instanceof EnderPearl || projectile instanceof Trident
            || !query.usesFlag(projectile.getWorld(), Flags.PVP) || EventGate.disabled(event)) {
            return;
        }
        final Entity hitEntity = event.getHitEntity();
        final Block hitBlock = event.getHitBlock();
        final ApplicableRegionSet target = hitEntity != null ? query.getApplicableRegions(hitEntity)
            : hitBlock != null ? query.getApplicableRegions(hitBlock) : null;
        if (target != null && !shooter.equals(hitEntity) && isolated(shooter, target)) {
            event.setCancelled(true);
            projectile.remove();
        }
    }

    private <E extends Event & Cancellable> void refuse(final E event, final Player player, final Entity target, final boolean explain) {
        if (query.usesFlag(target.getWorld(), Flags.PVP) && !EventGate.disabled(event)
            && isolated(player, query.getApplicableRegions(target))) {
            event.setCancelled(true);
            if (explain) {
                messages.sendDeny(player, Flags.PVP);
            }
        }
    }

    private <E extends Event & Cancellable> void refuse(final E event, final Player player, final Block block) {
        if (query.usesFlag(block.getWorld(), Flags.PVP) && !EventGate.disabled(event)
            && isolated(player, query.getApplicableRegions(block))) {
            event.setCancelled(true);
            messages.sendDeny(player, Flags.PVP);
        }
    }

    /**
     * Whether {@code target} explicitly allows pvp while the player's own spot denies it. The target is
     * read first, since most actions land outside any pvp region.
     */
    private boolean isolated(final Player player, final ApplicableRegionSet target) {
        final UUID uuid = player.getUniqueId();
        return target.queryExplicitState(Flags.PVP, uuid) == State.ALLOW
            && !query.getApplicableRegions(player).testState(Flags.PVP, uuid)
            && !Bypass.has(player);
    }
}
