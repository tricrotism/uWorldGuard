package com.tricrotism.uworldguard.integration;

import com.tricrotism.uworldguard.listeners.MovementListener;
import com.tricrotism.uworldguard.listeners.PlayerStateListener;
import com.tricrotism.uworldguard.listeners.TravelListener;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import org.bukkit.*;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Plain Folia fires no {@link PlayerTeleportEvent} or {@link PlayerPortalEvent} for an ender pearl,
 * chorus fruit, {@code teleportAsync}, {@code /tp} or a portal. This judges a pearl when it hits,
 * chorus fruit where it is eaten since its landing spot is not chosen yet, and a portal while the
 * player stands in it. A plugin's {@code teleportAsync} and {@code /tp} have nothing to catch, so
 * neither they nor where a portal lands are judged for entry.
 */
@NullMarked
public final class FoliaTeleports implements Listener {

    private final PlayerStateListener state;
    private final MovementListener movement;
    private final TravelListener travel;
    private final RegionContainerImpl container;

    FoliaTeleports(
        final PlayerStateListener state,
        final MovementListener movement,
        final TravelListener travel,
        final RegionContainerImpl container
    ) {
        this.container = container;
        this.state = state;
        this.movement = movement;
        this.travel = travel;
    }

    /**
     * Registers on Folia only, as Paper fires the teleport events itself. Call it only where
     * {@link CanvasTeleports} did not register, since Canvas is also a Folia and has a better bridge.
     */
    public static void register(
        final Plugin plugin,
        final PlayerStateListener state,
        final MovementListener movement,
        final TravelListener travel,
        final RegionContainerImpl container
    ) {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        } catch (final ClassNotFoundException e) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(
            new FoliaTeleports(state, movement, travel, container), plugin);
        plugin.getLogger().info("Folia detected: pearls, chorus fruit and portals are judged before they happen, "
            + "but other teleports fire no event here.");
    }

    /**
     * The owner lands where the pearl hits. A refused hit also removes the pearl, as a cancelled hit
     * alone lets it fly on.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlHit(final ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        final PlayerTeleportEvent mirror = new PlayerTeleportEvent(player, player.getLocation(), pearl.getLocation(),
            TeleportCause.ENDER_PEARL);
        state.onTeleport(mirror);
        if (!mirror.isCancelled()) {
            movement.onTeleport(mirror);
        }
        if (mirror.isCancelled()) {
            event.setCancelled(true);
            pearl.remove();
            return;
        }
        movement.onTeleported(mirror);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChorus(final PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.CHORUS_FRUIT) {
            return;
        }
        final Location at = event.getPlayer().getLocation();
        final PlayerTeleportEvent mirror = new PlayerTeleportEvent(event.getPlayer(), at, at, TeleportCause.CONSUMABLE_EFFECT);
        state.onTeleport(mirror);
        if (mirror.isCancelled()) {
            event.setCancelled(true);
        }
    }

    /**
     * Fires every tick something stands in a portal, and a cancel keeps it from going through. A
     * portal always leaves the world, so a spot in the world it leads to stands in for where it lands.
     * Only a player or a ridden entity can be refused, so anything else returns before that lookup.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalEnter(final EntityPortalEnterEvent event) {
        final Entity entity = event.getEntity();
        if (!(entity instanceof Player) && entity.isEmpty()) {
            return;
        }
        if (!SessionDispatch.ACTIVE && !container.anyRules()) {
            return;
        }
        final World target = leadsTo(entity.getWorld(), event.getPortalType());
        if (target == null) {
            return;
        }
        final Location from = entity.getLocation();
        final Location to = from.clone();
        to.setWorld(target);
        if (entity instanceof Player player) {
            final PlayerPortalEvent mirror = new PlayerPortalEvent(player, from, to);
            travel.onPortal(mirror);
            if (!mirror.isCancelled()) {
                state.onPortal(mirror);
            }
            if (mirror.isCancelled()) {
                event.setCancelled(true);
            }
        } else {
            final EntityPortalEvent mirror = new EntityPortalEvent(entity, from, to);
            travel.onVehiclePortal(mirror);
            if (mirror.isCancelled()) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * The world a portal of {@code type} in {@code from} leads to, or {@code null} for a portal that
     * goes nowhere here (an end gateway, or a server without the other world).
     */
    private static @Nullable World leadsTo(final World from, final PortalType type) {
        final World.Environment wanted = switch (type) {
            case NETHER ->
                from.getEnvironment() == World.Environment.NETHER ? World.Environment.NORMAL : World.Environment.NETHER;
            case ENDER ->
                from.getEnvironment() == World.Environment.THE_END ? World.Environment.NORMAL : World.Environment.THE_END;
            default -> null;
        };
        if (wanted == null) {
            return null;
        }
        for (final World world : Bukkit.getWorlds()) {
            if (world != from && world.getEnvironment() == wanted) {
                return world;
            }
        }
        return null;
    }
}
