package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.service.ChamberedPearlTracker;
import com.tricrotism.uworldguard.service.CollisionService;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.Locations;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces entry/exit and entry-level flags, runs per-region enter/leave effects (greeting/farewell,
 * commands, teleport, sounds), and applies continuous player state (game-mode, walk/fly speed,
 * flight). The handler returns immediately when the player has not crossed a block boundary, so the
 * region lookups only run on actual movement between blocks.
 */
@NullMarked
public final class MovementListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final Plugin plugin;
    private final RegionQuery query;
    private final MessageService messages;
    private final CollisionService collision;
    private final ChamberedPearlTracker pearls;
    private final Map<UUID, GameMode> savedGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, Float> savedWalkSpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Float> savedFlySpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> savedAllowFlight = new ConcurrentHashMap<>();
    private final Set<UUID> riddenMounts = ConcurrentHashMap.newKeySet();

    public MovementListener(final Plugin plugin, final RegionQuery query, final MessageService messages,
                            final CollisionService collision, final ChamberedPearlTracker pearls) {
        this.plugin = plugin;
        this.query = query;
        this.messages = messages;
        this.collision = collision;
        this.pearls = pearls;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final boolean bypass = player.hasPermission(BYPASS);

        final ApplicableRegionSet fromSet = query.getApplicableRegions(from);
        final ApplicableRegionSet toSet = query.getApplicableRegions(to);

        final Set<String> fromIds = idsOf(fromSet);
        final Set<String> toIds = idsOf(toSet);
        final boolean entering = !fromIds.containsAll(toIds);
        final boolean leaving = !toIds.containsAll(fromIds);

        if (!bypass && entering && !toSet.testState(Flags.ENTRY) && !isMember(toSet, uuid)) {
            event.setCancelled(true);
            messages.sendFlag(player, toSet.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
            return;
        }
        if (!bypass && leaving && !fromSet.testState(Flags.EXIT) && !isMember(fromSet, uuid)) {
            event.setCancelled(true);
            messages.sendFlag(player, fromSet.queryValue(Flags.EXIT_DENY_MESSAGE), "exit-denied");
            return;
        }

        if (!bypass && entering && !isMember(toSet, uuid) && levelDenied(player, toSet)) {
            event.setCancelled(true);
            messages.send(player, "entry-denied");
            return;
        }

        if (entering) {
            for (final ProtectedRegion region : toSet.getRegions()) {
                if (!fromIds.contains(region.getId())) {
                    onEnterRegion(player, region);
                }
            }
        }
        if (leaving) {
            for (final ProtectedRegion region : fromSet.getRegions()) {
                if (!toIds.contains(region.getId())) {
                    onLeaveRegion(player, region);
                }
            }
        }

        applyState(player, toSet);
    }

    /**
     * Entry/exit enforcement for players riding a living mount (pig, horse, strider). A mounted
     * player produces no {@link PlayerMoveEvent}, so without this the access flags are bypassed.
     * {@link EntityMoveEvent} is cancellable, so a denied crossing is simply cancelled. The
     * {@code riddenMounts} fast-path keeps this near-free when nobody is riding anything.
     * Per-region effects and continuous state still ride on {@link #onMove}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMountMove(final EntityMoveEvent event) {
        if (riddenMounts.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        final Entity mount = event.getEntity();
        if (!riddenMounts.contains(mount.getUniqueId())) {
            return;
        }
        if (deniedCrossing(mount, event.getFrom(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    /**
     * Same enforcement for boats and minecarts, which fire {@link VehicleMoveEvent} rather than
     * {@link EntityMoveEvent}. Not cancellable, so a denied crossing is undone by teleporting the
     * vehicle back to its previous position.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(final VehicleMoveEvent event) {
        final Location from = event.getFrom();
        final Vehicle vehicle = event.getVehicle();
        if (deniedCrossing(vehicle, from, event.getTo())) {
            vehicle.teleportAsync(from);
        }
    }

    /**
     * Shared entry/exit check for a moving vehicle/mount. Returns true when a non-bypassing player
     * passenger would cross a region boundary they may not. Cheap guards first: same-block and
     * no-boundary-change exits before any membership work.
     */
    private boolean deniedCrossing(final Entity vehicle, final Location from, final Location to) {
        if (from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return false;
        }
        final List<Entity> passengers = vehicle.getPassengers();
        if (passengers.isEmpty()) {
            return false;
        }

        final ApplicableRegionSet fromSet = query.getApplicableRegions(from);
        final ApplicableRegionSet toSet = query.getApplicableRegions(to);
        final boolean entering = !isInside(fromSet, toSet);
        final boolean leaving = !isInside(toSet, fromSet);
        if (!entering && !leaving) {
            return false;
        }

        for (final Entity passenger : passengers) {
            if (!(passenger instanceof Player player) || player.hasPermission(BYPASS)) {
                continue;
            }
            final UUID uuid = player.getUniqueId();
            if (entering && !toSet.testState(Flags.ENTRY) && !isMember(toSet, uuid)) {
                messages.sendFlag(player, toSet.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
                return true;
            }
            if (leaving && !fromSet.testState(Flags.EXIT) && !isMember(fromSet, uuid)) {
                messages.sendFlag(player, fromSet.queryValue(Flags.EXIT_DENY_MESSAGE), "exit-denied");
                return true;
            }
        }
        return false;
    }

    /**
     * Blocks mounting a vehicle that sits in a region the player may not enter (otherwise a player
     * could mount an animal standing inside a no-entry region and be carried in), and starts
     * tracking the mount so {@link #onMountMove} enforces its movement.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(final EntityMountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final Entity mount = event.getMount();
        if (!player.hasPermission(BYPASS)) {
            final ApplicableRegionSet set = query.getApplicableRegions(mount.getLocation());
            if (!set.testState(Flags.ENTRY) && !isMember(set, player.getUniqueId())) {
                event.setCancelled(true);
                messages.sendFlag(player, set.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
                return;
            }
        }
        riddenMounts.add(mount.getUniqueId());
    }

    @EventHandler
    public void onDismount(final EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            riddenMounts.remove(event.getDismounted().getUniqueId());
        }
    }

    private void onEnterRegion(final Player player, final ProtectedRegion region) {
        final String greeting = region.getFlag(Flags.GREETING);
        if (greeting != null) {
            player.sendMessage(messages.render(greeting, player));
        }
        runCommand(player, region.getFlag(Flags.COMMAND_ON_ENTRY), false);
        runCommand(player, region.getFlag(Flags.CONSOLE_COMMAND_ON_ENTRY), true);
        playSound(player, region.getFlag(Flags.PLAY_SOUNDS));
        teleport(player, region.getFlag(Flags.TELEPORT_ON_ENTRY));
        if (region.getFlag(Flags.CHAMBERED_ENDERPEARL) == State.DENY) {
            pearls.removeFor(player.getUniqueId());
        }
    }

    private void onLeaveRegion(final Player player, final ProtectedRegion region) {
        final String farewell = region.getFlag(Flags.FAREWELL);
        if (farewell != null) {
            player.sendMessage(messages.render(farewell, player));
        }
        runCommand(player, region.getFlag(Flags.COMMAND_ON_EXIT), false);
        runCommand(player, region.getFlag(Flags.CONSOLE_COMMAND_ON_EXIT), true);
        teleport(player, region.getFlag(Flags.TELEPORT_ON_EXIT));
    }

    private void runCommand(final Player player, final @Nullable String raw, final boolean console) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final String command = messages.expand(player, raw.replace("%player%", player.getName()));
        if (console) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else {
            Bukkit.dispatchCommand(player, command);
        }
    }

    private void playSound(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final String[] parts = raw.split(":");
        final String sound = parts[0].trim();
        final float volume = parts.length >= 2 ? parseFloat(parts[1], 1f) : 1f;
        final float pitch = parts.length >= 3 ? parseFloat(parts[2], 1f) : 1f;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Teleports the moving player on their own entity scheduler (next tick), which is Folia-safe
     * and avoids re-entering the move event we are currently handling.
     */
    private void teleport(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final Location target = Locations.parse(messages.expand(player, raw));
        if (target != null) {
            player.getScheduler().run(plugin, task -> player.teleport(target), null);
        }
    }

    private boolean levelDenied(final Player player, final ApplicableRegionSet toSet) {
        final int level = player.getLevel();
        final Integer min = levelThreshold(player, toSet.queryValue(Flags.ENTRY_MIN_LEVEL));
        if (min != null && level < min) {
            return true;
        }
        final Integer max = levelThreshold(player, toSet.queryValue(Flags.ENTRY_MAX_LEVEL));
        return max != null && level > max;
    }

    private @Nullable Integer levelThreshold(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(messages.expand(player, raw).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static float parseFloat(final String value, final float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Applies the region's continuous player state (game-mode, walk/fly speed, flight) while inside,
     * restoring each to the value the player had before entering once the override no longer applies.
     */
    private void applyState(final Player player, final ApplicableRegionSet toSet) {
        final UUID uuid = player.getUniqueId();

        final GameMode mode = parseGameMode(toSet.queryValue(Flags.GAME_MODE));
        if (mode != null) {
            if (player.getGameMode() != mode) {
                savedGameModes.putIfAbsent(uuid, player.getGameMode());
                player.setGameMode(mode);
            }
        } else {
            final GameMode saved = savedGameModes.remove(uuid);
            if (saved != null && player.getGameMode() != saved) {
                player.setGameMode(saved);
            }
        }

        final Double walk = toSet.queryValue(Flags.WALK_SPEED);
        if (walk != null) {
            savedWalkSpeed.putIfAbsent(uuid, player.getWalkSpeed());
            player.setWalkSpeed(clampSpeed(walk.floatValue()));
        } else {
            final Float saved = savedWalkSpeed.remove(uuid);
            if (saved != null) {
                player.setWalkSpeed(saved);
            }
        }

        final Double fly = toSet.queryValue(Flags.FLY_SPEED);
        if (fly != null) {
            savedFlySpeed.putIfAbsent(uuid, player.getFlySpeed());
            player.setFlySpeed(clampSpeed(fly.floatValue()));
        } else {
            final Float saved = savedFlySpeed.remove(uuid);
            if (saved != null) {
                player.setFlySpeed(saved);
            }
        }

        if (Boolean.TRUE.equals(toSet.queryValue(Flags.FLY))) {
            if (!player.getAllowFlight()) {
                savedAllowFlight.putIfAbsent(uuid, Boolean.FALSE);
                player.setAllowFlight(true);
            }
        } else {
            final Boolean saved = savedAllowFlight.remove(uuid);
            if (saved != null && !saved) {
                player.setAllowFlight(false);
            }
        }

        collision.set(player, Boolean.TRUE.equals(toSet.queryValue(Flags.DISABLE_COLLISION)));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        messages.clear(uuid);
        collision.set(player, false);
        pearls.clear(uuid);

        final GameMode mode = savedGameModes.remove(uuid);
        if (mode != null) {
            player.setGameMode(mode);
        }
        final Float walk = savedWalkSpeed.remove(uuid);
        if (walk != null) {
            player.setWalkSpeed(walk);
        }
        final Float fly = savedFlySpeed.remove(uuid);
        if (fly != null) {
            player.setFlySpeed(fly);
        }
        final Boolean allowFlight = savedAllowFlight.remove(uuid);
        if (allowFlight != null && !allowFlight) {
            player.setAllowFlight(false);
        }
    }

    private static float clampSpeed(final float value) {
        if (value < -1f) {
            return -1f;
        }
        return Math.min(value, 1f);
    }

    private static @Nullable GameMode parseGameMode(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return GameMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> idsOf(final ApplicableRegionSet set) {
        final List<ProtectedRegion> regions = set.getRegions();
        if (regions.isEmpty()) {
            return Set.of();
        }
        final Set<String> ids = HashSet.newHashSet(regions.size() * 2);
        for (final ProtectedRegion region : regions) {
            ids.add(region.getId());
        }
        return ids;
    }

    private static boolean isMember(final ApplicableRegionSet set, final UUID uuid) {
        for (final ProtectedRegion region : set.getRegions()) {
            if (region.isMember(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if every region in {@code inner} also appears in {@code outer} (no new boundary crossed).
     */
    private static boolean isInside(final ApplicableRegionSet outer, final ApplicableRegionSet inner) {
        final Set<String> outerIds = idsOf(outer);
        for (final ProtectedRegion region : inner.getRegions()) {
            if (!outerIds.contains(region.getId())) {
                return false;
            }
        }
        return true;
    }
}
