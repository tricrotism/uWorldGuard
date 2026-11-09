package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NullMarked;

/**
 * Item-use flags: {@code disable-completely} (right-click use, melee weapon, totem resurrect),
 * {@code disable-throw} (egg / snowball / ender pearl / xp bottle), {@code wind-charge},
 * {@code villager-trade}, and {@code deny-item-drops} / {@code deny-item-pickup}. Each handler
 * filters cheaply and returns fast.
 *
 * <p>Every handler that resolves a region set first asks the registry whether any region on the
 * server sets the flag at all — a bitset test per world. On a server that uses none of these flags
 * that is the whole cost of the handler: no region resolved, nothing allocated.
 */
@NullMarked
public final class ItemUseListener implements Listener {

    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final MessageService messages;

    public ItemUseListener(
        final RegionContainerImpl container, final RegionQuery query, final MessageService messages
    ) {
        this.container = container;
        this.query = query;
        this.messages = messages;
    }

    /**
     * Not {@code ignoreCancelled}: for this event that means "the block was denied", which is true of
     * every click at air and of a throwable used against a protected block, and the item is still
     * used in both.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(final PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY || EventGate.disabled(event)) {
            return;
        }
        if (event.getItem() == null || !container.anyRegionUses(Flags.DISABLE_COMPLETELY)) {
            return;
        }
        final Player player = event.getPlayer();
        final Material item = event.getItem().getType();
        if (query.getApplicableRegions(player).flagSetContains(Flags.DISABLE_COMPLETELY, item, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.DISABLE_COMPLETELY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(final EntityDamageByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!query.usesFlag(event.getEntity().getWorld(), Flags.DISABLE_COMPLETELY)) {
            return;
        }
        final Material weapon = player.getInventory().getItemInMainHand().getType();
        if (weapon.isAir()) {
            return;
        }
        if (query.getApplicableRegions(event.getEntity()).flagSetContains(Flags.DISABLE_COMPLETELY, weapon, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.DISABLE_COMPLETELY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrect(final EntityResurrectEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!container.anyRegionUses(Flags.DISABLE_COMPLETELY)) {
            return;
        }
        final EntityEquipment equipment = player.getEquipment();
        final boolean holdingTotem = equipment.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
            || equipment.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (!holdingTotem) {
            return;
        }
        if (query.getApplicableRegions(player).flagSetContains(Flags.DISABLE_COMPLETELY, Material.TOTEM_OF_UNDYING, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(final ProjectileLaunchEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!container.anyRegionUses(Flags.DISABLE_THROW)) {
            return;
        }
        final Projectile projectile = event.getEntity();
        if (!(projectile instanceof Egg || projectile instanceof Snowball
            || projectile instanceof EnderPearl || projectile instanceof ThrownExpBottle)) {
            return;
        }
        final ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }
        if (Boolean.TRUE.equals(query.getApplicableRegions(player).queryValue(Flags.DISABLE_THROW, player.getUniqueId()))) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.DISABLE_THROW);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindChargeThrow(final ProjectileLaunchEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof WindCharge windCharge)
            || !(windCharge.getShooter() instanceof Player player)
            || !query.usesFlag(player.getWorld(), Flags.WIND_CHARGE)) {
            return;
        }
        if (!query.testState(player, Flags.WIND_CHARGE)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.WIND_CHARGE);
        }
    }

    /**
     * Wind-charge knockback, the thrower included, so a charge thrown from outside cannot launch
     * anyone standing in a deny region. Uses Paper's event because Bukkit's
     * {@code EntityKnockbackByEntityEvent} is deprecated for removal. Only the base event declares a
     * handler list, so registering against this parent still receives every push subclass.
     *
     * <p>A burst is an explosion, and Paper names whoever caused it as the pusher: the thrower for a
     * thrown charge, the charge itself only when nobody threw it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindChargeKnockback(final EntityPushedByEntityAttackEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Entity pushedBy = event.getPushedBy();
        if (event.getCause() != EntityKnockbackEvent.Cause.EXPLOSION
            || !(pushedBy instanceof Player || pushedBy instanceof AbstractWindCharge)) {
            return;
        }
        final Entity victim = event.getEntity();
        if (!query.usesFlag(victim.getWorld(), Flags.WIND_CHARGE)) {
            return;
        }
        final boolean allowed = pushedBy instanceof Player player
            ? query.getApplicableRegions(victim).testState(Flags.WIND_CHARGE, player.getUniqueId())
            : query.testState(victim, Flags.WIND_CHARGE);
        if (allowed) {
            return;
        }
        if (pushedBy instanceof Player player && Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(final PlayerInteractEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getRightClicked() instanceof AbstractVillager villager)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!query.getApplicableRegions(villager).testState(Flags.VILLAGER_TRADE, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.VILLAGER_TRADE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final World world = player.getWorld();
        if (!query.usesFlag(world, Flags.ITEM_DROP) && !query.usesFlag(world, Flags.DENY_ITEM_DROPS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(player);
        if (!set.testState(Flags.ITEM_DROP, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.ITEM_DROP);
            return;
        }
        if (set.worldUses(Flags.DENY_ITEM_DROPS)
            && set.flagSetContains(Flags.DENY_ITEM_DROPS, event.getItemDrop().getItemStack().getType(), player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.DENY_ITEM_DROPS);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final World world = player.getWorld();
        if (!query.usesFlag(world, Flags.ITEM_PICKUP) && !query.usesFlag(world, Flags.DENY_ITEM_PICKUP)) {
            return;
        }
        // A cancelled pickup fires again every tick, so the item is only read when the list is in use.
        final ApplicableRegionSet set = query.getApplicableRegions(player);
        if (!set.testState(Flags.ITEM_PICKUP, player.getUniqueId())
            || set.worldUses(Flags.DENY_ITEM_PICKUP)
            && set.flagSetContains(Flags.DENY_ITEM_PICKUP, event.getItem().getItemStack().getType(), player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }
}
