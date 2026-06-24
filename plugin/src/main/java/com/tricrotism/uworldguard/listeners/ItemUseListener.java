package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Material;
import org.bukkit.entity.*;
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
 */
@NullMarked
public final class ItemUseListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;
    private final MessageService messages;

    public ItemUseListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(final PlayerInteractEvent event) {
        if (event.getItem() == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        final Material item = event.getItem().getType();
        if (query.getApplicableRegions(player.getLocation()).flagSetContains(Flags.DISABLE_COMPLETELY, item)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        final Material weapon = player.getInventory().getItemInMainHand().getType();
        if (weapon.isAir()) {
            return;
        }
        if (query.getApplicableRegions(event.getEntity().getLocation()).flagSetContains(Flags.DISABLE_COMPLETELY, weapon)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrect(final EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final EntityEquipment equipment = player.getEquipment();
        final boolean holdingTotem = equipment.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
            || equipment.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (!holdingTotem) {
            return;
        }
        if (query.getApplicableRegions(player.getLocation()).flagSetContains(Flags.DISABLE_COMPLETELY, Material.TOTEM_OF_UNDYING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(final ProjectileLaunchEvent event) {
        final Projectile projectile = event.getEntity();
        if (!(projectile instanceof Egg || projectile instanceof Snowball
            || projectile instanceof EnderPearl || projectile instanceof ThrownExpBottle)) {
            return;
        }
        final ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        if (Boolean.TRUE.equals(query.getApplicableRegions(player.getLocation()).queryValue(Flags.DISABLE_THROW))) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindCharge(final ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractWindCharge windCharge)) {
            return;
        }

        if (!(windCharge.getShooter() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }

        if (!query.testState(player.getLocation(), Flags.WIND_CHARGE)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof AbstractVillager villager)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        if (!query.getApplicableRegions(villager.getLocation()).testState(Flags.VILLAGER_TRADE)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        final Material item = event.getItemDrop().getItemStack().getType();
        if (query.getApplicableRegions(player.getLocation()).flagSetContains(Flags.DENY_ITEM_DROPS, item)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.hasPermission(BYPASS)) {
            return;
        }
        final Material item = event.getItem().getItemStack().getType();
        if (query.getApplicableRegions(player.getLocation()).flagSetContains(Flags.DENY_ITEM_PICKUP, item)) {
            event.setCancelled(true);
        }
    }
}
