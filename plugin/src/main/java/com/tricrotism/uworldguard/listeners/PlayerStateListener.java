package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * Enforces sleep, enderpearl/chorus teleport, chest-access, respawn-anchors, ride,
 * invincible/godmode, fall-damage, natural-health-regen, natural-hunger-drain, and item-durability.
 */
@NullMarked
public final class PlayerStateListener implements Listener {


    private final RegionQuery query;
    private final MessageService messages;

    public PlayerStateListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSleep(final PlayerBedEnterEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getBed(), Flags.SLEEP, event.getPlayer())) {
            if (Bypass.has(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        final var flag = switch (event.getCause().name()) {
            case "ENDER_PEARL" -> Flags.ENDERPEARL;
            case "CHORUS_FRUIT" -> Flags.CHORUS_TELEPORT;
            default -> null;
        };
        if (flag != null && !query.testState(event.getTo(), flag)
            && !Bypass.has(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (SessionDispatch.ACTIVE && SessionDispatch.testMove(
            event.getPlayer(), event.getFrom(), event.getTo(), SessionDispatch.Move.TELEPORT) != null) {
            event.setCancelled(true);
            return;
        }
        checkExitViaTeleport(event);
    }

    /**
     * Portal travel is a teleport, but {@code PlayerPortalEvent} declares its own handler list, so it
     * never reaches {@link #onTeleport}. Without this, stepping into a nether portal walks straight
     * out of a region that {@code exit-via-teleport} was meant to hold you in.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        if (!EventGate.disabled(event)) {
            checkExitViaTeleport(event);
        }
    }

    /**
     * Teleporting sidesteps the movement path entirely, so a region that denies exit can still be
     * left by any {@code /tp}, home or warp. {@code exit-via-teleport} closes that: when it denies,
     * a teleport that would take the player out of a region they cannot walk out of is refused too.
     *
     * <p>Only regions the player is actually leaving are considered — a teleport within the same
     * region, or into one, is never blocked by this.
     */
    private void checkExitViaTeleport(final PlayerTeleportEvent event) {
        final Location to = event.getTo();
        if (to == null) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet from = query.getApplicableRegions(event.getFrom());
        if (from.isEmpty() || Bypass.has(player)) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (from.testState(Flags.EXIT_VIA_TELEPORT, uuid) || from.testState(Flags.EXIT, uuid)) {
            return;
        }
        if (Boolean.TRUE.equals(from.queryValue(Flags.EXIT_OVERRIDE))) {
            return;
        }
        if (!from.testState(Flags.ENTRY, uuid) && !isMember(from, uuid)) {
            return;
        }
        final ApplicableRegionSet destination = query.getApplicableRegions(to);
        for (int i = 0, n = from.size(); i < n; i++) {
            if (!contains(destination, from.get(i))) {
                event.setCancelled(true);
                messages.sendFlag(player, from.queryValue(Flags.EXIT_DENY_MESSAGE), "exit-denied");
                return;
            }
        }
    }

    private static boolean isMember(final ApplicableRegionSet set, final UUID uuid) {
        for (int i = 0, n = set.size(); i < n; i++) {
            if (set.get(i).isMember(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code region} is in {@code set}, by identity. Both sets come from the same world's
     * manager on a same-world teleport, so a shared region is the same object — and this walks the set
     * directly instead of materialising the unmodifiable view {@code getRegions()} builds.
     */
    private static boolean contains(final ApplicableRegionSet set, final ProtectedRegion region) {
        for (int i = 0, n = set.size(); i < n; i++) {
            if (set.get(i) == region) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestAccess(final PlayerInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (block.getType() == Material.RESPAWN_ANCHOR) {
            final ApplicableRegionSet anchorSet = query.getApplicableRegions(block);
            if (!anchorSet.testBuild(player.getUniqueId(), Flags.RESPAWN_ANCHORS) && !Bypass.has(player)) {
                event.setCancelled(true);
                messages.sendDeny(player, Flags.RESPAWN_ANCHORS, anchorSet.queryValue(Flags.DENY_MESSAGE));
            }
            return;
        }
        if (!(block.getState(false) instanceof Container)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!set.testBuild(player.getUniqueId(), Flags.CHEST_ACCESS)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.CHEST_ACCESS, set.queryValue(Flags.DENY_MESSAGE));
        }
    }

    /**
     * {@code natural-health-regen} covers only the server's own regeneration — the passive and
     * saturation ticks. Healing from a potion, a golden apple, or the {@code heal-amount} flag is a
     * deliberate act and is left alone.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(final EntityRegainHealthEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final RegainReason reason = event.getRegainReason();
        if (reason != RegainReason.REGEN && reason != RegainReason.SATIATED) {
            return;
        }
        if (!query.testState(player, Flags.NATURAL_HEALTH_REGEN)) {
            event.setCancelled(true);
        }
    }

    /**
     * Only the drain direction is gated — eating inside the region must still work.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(final FoodLevelChangeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }
        if (!query.testState(player, Flags.NATURAL_HUNGER_DRAIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRide(final VehicleEnterEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntered() instanceof Player player)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getVehicle());
        if (!set.testBuild(player.getUniqueId(), Flags.RIDE)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || EventGate.disabled(event)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(player);
        if (Boolean.TRUE.equals(set.queryValue(Flags.INVINCIBLE))
            || Boolean.TRUE.equals(set.queryValue(Flags.GODMODE))) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
            && !set.testState(Flags.FALL_DAMAGE, player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(final PlayerItemDamageEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!query.testState(player, Flags.ITEM_DURABILITY)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }
}
