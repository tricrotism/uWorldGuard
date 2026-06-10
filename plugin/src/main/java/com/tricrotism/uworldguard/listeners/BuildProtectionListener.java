package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Enforces the build, block-break, block-place, interact, use, and pvp flags.
 */
@NullMarked
public final class BuildProtectionListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;
    private final MessageService messages;

    public BuildProtectionListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getBlock().getLocation());
        final Material type = event.getBlock().getType();
        if (set.flagSetContains(Flags.DENY_BLOCK_BREAK, type)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
            return;
        }
        if (set.flagSetContains(Flags.ALLOW_BLOCK_BREAK, type)) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (!set.canBuild(uuid) || !set.testState(Flags.BLOCK_BREAK)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getBlock().getLocation());
        final Material type = event.getBlock().getType();
        if (set.flagSetContains(Flags.DENY_BLOCK_PLACE, type)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
            return;
        }
        if (set.flagSetContains(Flags.ALLOW_BLOCK_PLACE, type)) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (!set.canBuild(uuid) || !set.testState(Flags.BLOCK_PLACE)) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getClickedBlock().getLocation());
        if (!set.canBuild(player.getUniqueId()) && (!set.testState(Flags.INTERACT) || !set.testState(Flags.USE))) {
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.hasPermission(BYPASS)) {
            return;
        }
        final Location loc = event.getEntity().getLocation();
        if (!query.testState(loc, Flags.PVP)) {
            event.setCancelled(true);
        }
    }

    private static @Nullable Player resolvePlayer(final Object damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
