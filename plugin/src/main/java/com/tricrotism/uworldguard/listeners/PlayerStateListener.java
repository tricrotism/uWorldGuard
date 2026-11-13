package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces sleep, enderpearl/chorus teleport, chest-access, respawn-anchors, ride,
 * invincible/godmode, fall-damage, natural-health-regen, natural-hunger-drain, blocked-effects, and
 * item-durability.
 */
@NullMarked
public final class PlayerStateListener implements Listener {

    /**
     * Every block whose state is a {@link Container}, so chest-access is decided from the type alone
     * without building a block state. Built from the {@code Container} subtypes: barrel, brewing stand, chest,
     * crafter, dispenser, dropper, the furnaces, hopper and shulker box. Copper chests are chests.
     */
    private static final Set<Material> CONTAINERS = containers();

    private final RegionQuery query;
    private final MessageService messages;

    public PlayerStateListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    private static Set<Material> containers() {
        final EnumSet<Material> set = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.BREWING_STAND, Material.CRAFTER, Material.DISPENSER, Material.DROPPER,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER);
        for (final Material material : Material.values()) {
            final String name = material.name();
            if (!material.isLegacy() && (name.endsWith("SHULKER_BOX") || name.endsWith("COPPER_CHEST"))) {
                set.add(material);
            }
        }
        return set;
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
        // Note: since 1.21.4 chorus fruit teleports as a consume effect, and CHORUS_FRUIT is never sent
        final var flag = switch (event.getCause().name()) {
            case "ENDER_PEARL" -> Flags.ENDERPEARL;
            case "CHORUS_FRUIT", "CONSUMABLE_EFFECT" -> Flags.CHORUS_TELEPORT;
            default -> null;
        };
        if (flag != null && teleportRefused(event, flag)) {
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
     * As in WorldGuard, the flag is tested for the player both where the pearl or fruit was used and
     * where it lands. So a deny also stops someone throwing their way out, and a {@code -g} group applies.
     */
    private boolean teleportRefused(final PlayerTeleportEvent event, final StateFlag flag) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        ApplicableRegionSet set = query.getApplicableRegions(event.getFrom());
        if (set.testState(flag, id)) {
            set = query.getApplicableRegions(event.getTo());
            if (set.testState(flag, id)) {
                return false;
            }
        }
        if (Bypass.has(player)) {
            return false;
        }
        messages.sendRegionDeny(player, flag, set);
        return true;
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
        if (!query.usesFlag(event.getFrom().getWorld(), Flags.EXIT_VIA_TELEPORT)) {
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
        if (Boolean.TRUE.equals(from.queryValue(Flags.EXIT_OVERRIDE, player.getUniqueId()))) {
            return;
        }
        if (!from.testState(Flags.ENTRY, uuid) && !isMember(from, uuid)) {
            return;
        }
        final ApplicableRegionSet destination = query.getApplicableRegions(to);
        for (int i = 0, n = from.size(); i < n; i++) {
            if (!contains(destination, from.get(i))) {
                event.setCancelled(true);
                messages.sendFlag(player, from.queryValue(Flags.EXIT_DENY_MESSAGE, player.getUniqueId()), "exit-denied");
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final Player player = event.getPlayer();
        final Material type = block.getType();
        final boolean mainHand = event.getHand() == EquipmentSlot.HAND;
        if (type == Material.RESPAWN_ANCHOR) {
            final ApplicableRegionSet anchorSet = query.getApplicableRegions(block);
            if (!anchorSet.testBuild(player.getUniqueId(), Flags.RESPAWN_ANCHORS) && !Bypass.has(player)
                && !anchorSet.flagSetContains(Flags.ALLOW_INTERACT, Material.RESPAWN_ANCHOR, player.getUniqueId())) {
                event.setCancelled(true);
                if (mainHand) {
                    messages.sendRegionDeny(player, Flags.RESPAWN_ANCHORS, anchorSet);
                }
            }
            return;
        }
        if (!CONTAINERS.contains(type)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!set.testBuild(player.getUniqueId(), Flags.CHEST_ACCESS)) {
            if (Bypass.has(player) || set.flagSetContains(Flags.ALLOW_INTERACT, type, player.getUniqueId())) {
                return;
            }
            event.setCancelled(true);
            if (mainHand) {
                messages.sendRegionDeny(player, Flags.CHEST_ACCESS, set);
            }
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
        if (query.usesFlag(player.getWorld(), Flags.NATURAL_HEALTH_REGEN)
            && !query.testState(player, Flags.NATURAL_HEALTH_REGEN)) {
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
        if (query.usesFlag(player.getWorld(), Flags.NATURAL_HUNGER_DRAIN)
            && !query.testState(player, Flags.NATURAL_HUNGER_DRAIN)) {
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
        final World world = player.getWorld();
        final boolean invincibility =
            query.usesFlag(world, Flags.INVINCIBLE) || query.usesFlag(world, Flags.GODMODE);
        final boolean fall = (event.getCause() == EntityDamageEvent.DamageCause.FALL
            || event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL)
            && query.usesFlag(world, Flags.FALL_DAMAGE);
        if (!invincibility && !fall) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(player);
        if (invincibility && (Boolean.TRUE.equals(set.queryValue(Flags.INVINCIBLE, player.getUniqueId()))
            || Boolean.TRUE.equals(set.queryValue(Flags.GODMODE, player.getUniqueId())))) {
            event.setCancelled(true);
            return;
        }
        if (fall && !set.testState(Flags.FALL_DAMAGE, player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Refuses a blocked effect as it is applied. The tick service strips blocked effects once a
     * second, which is too slow for a wither rose: it re-applies Wither every tick the player stands
     * in it, so damage lands between strips. The tick still clears effects a player walks in with.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(final EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getNewEffect() == null
            || EventGate.disabled(event)) {
            return;
        }
        if (!query.usesFlag(player.getWorld(), Flags.BLOCKED_EFFECTS)) {
            return;
        }
        final Set<PotionEffect> blocked = query.queryValue(player, Flags.BLOCKED_EFFECTS);
        if (blocked == null || Bypass.has(player)) {
            return;
        }
        final PotionEffectType type = event.getModifiedType();
        for (final PotionEffect effect : blocked) {
            if (effect.getType() == type) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(final PlayerItemDamageEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!query.usesFlag(player.getWorld(), Flags.ITEM_DURABILITY)) {
            return;
        }
        if (!query.testState(player, Flags.ITEM_DURABILITY)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }
}
