package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.event.RegionEnterEvent;
import com.tricrotism.uworldguard.event.RegionExitEvent;
import com.tricrotism.uworldguard.flags.BooleanFlag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.service.ChamberedPearlTracker;
import com.tricrotism.uworldguard.service.CollisionService;
import com.tricrotism.uworldguard.service.PendingRestores;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.Locations;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces entry/exit and entry-level flags, runs per-region enter/leave effects (greeting/farewell,
 * commands, teleport, sounds), and applies continuous player state (game-mode, walk/fly speed,
 * flight). The handler returns immediately when the player has not crossed a block boundary, so the
 * region lookups only run on actual movement between blocks.
 */
@NullMarked
public final class MovementListener implements Listener {

    private static final String NOTIFY_PERMISSION = "uworldguard.notify";

    private final Plugin plugin;
    private final RegionQuery query;
    private final MessageService messages;
    private final CollisionService collision;
    private final ChamberedPearlTracker pearls;
    private final ChatTags chatTags;
    private final PendingRestores restores;
    private final Map<UUID, GameMode> savedGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, Float> savedWalkSpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Float> savedFlySpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> savedAllowFlight = new ConcurrentHashMap<>();
    private final Set<UUID> riddenMounts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> lastPosition = new ConcurrentHashMap<>();

    private volatile boolean taskMode;
    private volatile int taskTicks;
    /**
     * Polls between containment sweeps, chosen so a sweep lands roughly once a second.
     */
    private volatile int sweepEvery;
    /**
     * One repeating poll per online player, so the task can be cancelled when they leave and when a
     * reload retunes the interval. Folia retires an entity's tasks with the entity, so the handle is
     * held for the reload case rather than for the quit case.
     */
    private final Map<UUID, ScheduledTask> pollTasks = new ConcurrentHashMap<>();

    public MovementListener(
        final Plugin plugin, final RegionQuery query, final MessageService messages,
        final CollisionService collision, final ChamberedPearlTracker pearls, final ChatTags chatTags,
        final PendingRestores restores, final Settings settings
    ) {
        this.plugin = plugin;
        this.query = query;
        this.messages = messages;
        this.collision = collision;
        this.pearls = pearls;
        this.chatTags = chatTags;
        this.restores = restores;
        readSettings(settings);
    }

    private void readSettings(final Settings settings) {
        this.taskMode = settings.movementMode() == Settings.MovementMode.TASK;
        this.taskTicks = settings.movementTaskTicks();
        this.sweepEvery = Math.max(1, Math.round(20f / settings.movementTaskTicks()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (taskMode) {
            return;
        }
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet fromSet = query.getApplicableRegions(from);
        final ApplicableRegionSet toSet = query.getApplicableRegions(to);

        if (processCrossing(player, to.getWorld(), fromSet, toSet)) {
            event.setCancelled(true);
            return;
        }
        if (SessionDispatch.ACTIVE
            && SessionDispatch.testMove(player, from, to, SessionDispatch.selfMove(player)) != null) {
            event.setCancelled(true);
            return;
        }
        applyState(player, toSet);
    }

    /**
     * Starts the polled movement tracker when {@code movement.mode: TASK} is configured; a no-op in
     * event mode. Players online at this point are picked up here, later ones by {@link #onJoin}.
     *
     * <p>Each player gets their own repeating task on their own entity scheduler, so the position
     * read and any teleport happen on the thread that owns them. A global task fanning out to every
     * player instead scheduled one entity task per player per interval — at the default interval that
     * is five task objects per player per second, all of them queued and woken by the global thread,
     * for work that only ever touches one player.
     */
    public void start() {
        if (!taskMode) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            startPoll(player);
        }
    }

    /**
     * Schedules {@code player}'s poll, replacing any it already had. Returns silently when the
     * scheduler refuses, which it does for a player already on their way out.
     *
     * <p>No retired callback: Folia cancels the task with the player, {@link #onQuit} drops the
     * handle, and a retired callback firing after a fast relog would drop the *new* handle instead,
     * leaving a poll nothing can cancel on reload.
     */
    private void startPoll(final Player player) {
        final UUID uuid = player.getUniqueId();
        stopPoll(uuid);
        final AtomicInteger sweepTick = new AtomicInteger();
        final ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, _ -> {
            final boolean sweep = sweepTick.incrementAndGet() >= sweepEvery;
            if (sweep) {
                sweepTick.set(0);
            }
            poll(player, sweep);
        }, null, taskTicks, taskTicks);
        if (task != null) {
            pollTasks.put(uuid, task);
        }
    }

    private void stopPoll(final UUID uuid) {
        final ScheduledTask existing = pollTasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }
    }

    /**
     * Re-reads the movement settings and restarts the poll to match, so {@code /uwg reload} can switch
     * between EVENT and TASK or retune the interval without a restart. Positions tracked for the old
     * mode are dropped: in EVENT mode they go stale immediately, and on the way back into TASK mode a
     * stale position would be judged as a crossing the player never made.
     */
    public void applySettings(final Settings settings) {
        stop();
        readSettings(settings);
        lastPosition.clear();
        start();
    }

    /**
     * Cancels every poll. Paper drops a plugin's tasks on disable anyway, but holding the handles
     * keeps reload honest — without them a mode switch would leave the previous polls running
     * alongside the new ones, double-enforcing every crossing.
     */
    public void stop() {
        for (final ScheduledTask task : pollTasks.values()) {
            task.cancel();
        }
        pollTasks.clear();
    }

    /**
     * Puts back everything {@link #applyState} overrode, for players still online when the plugin
     * goes away. Leaving a region restores it and so does quitting, but a disable does neither: a
     * player standing in a {@code game-mode} / {@code fly} region keeps creative and flight for good,
     * and one inside a {@code hide-players} region keeps every other player invisible for the rest of
     * their session — the hidden-entity list CraftBukkit keeps is keyed by plugin and is not swept
     * when one unloads.
     *
     * <p>Every restore runs inline, and only for the players the disabling thread already owns.
     * Scheduling was the obvious way to reach the rest and does not work: an entity task never runs
     * inline, the earliest it could fire is the owning region's next tick, and by then this plugin's
     * tasks have been cancelled with it. Worse, the plugin counts as disabled for the whole of
     * {@code onDisable} — {@code JavaPlugin.setEnabled} clears the flag before calling it — so the
     * scheduling call can throw and take the rest of the teardown, region saving included, with it.
     *
     * <p>The owed state is read from {@link PendingRestores} rather than from the four maps here,
     * because that is the copy that also survives a crash — the maps and it are kept in step by
     * {@link #applyState}. Anything this cannot reach simply stays in it, and the final write on the
     * way out leaves it on disk to be replayed when that player next logs in. Hiding is the
     * exception: it is session state a disconnect clears anyway, so there is nothing to persist.
     */
    public void shutdown() {
        for (final Map.Entry<UUID, PendingRestores.State> owed : restores.outstanding().entrySet()) {
            final UUID uuid = owed.getKey();
            final Player player = Bukkit.getPlayer(uuid);
            if (player == null || !plugin.getServer().isOwnedByCurrentRegion(player)) {
                continue;
            }
            apply(player, owed.getValue());
            restores.forget(uuid);
        }
        savedGameModes.clear();
        savedWalkSpeed.clear();
        savedFlySpeed.clear();
        savedAllowFlight.clear();
        restores.flushNow();

        for (final UUID viewerId : hidden) {
            final Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !plugin.getServer().isOwnedByCurrentRegion(viewer)) {
                continue;
            }
            for (final Player other : Bukkit.getOnlinePlayers()) {
                if (other != viewer) {
                    viewer.showPlayer(plugin, other);
                }
            }
        }
        hidden.clear();
    }

    /**
     * Drops every tracked position that points into {@code world}. The values are {@link Location}s,
     * which hold a strong reference to their world — left behind, an unloaded world stays reachable
     * through anyone who was standing in it and is still online. Same reason
     * {@code WandSelectionProvider} forgets its corners.
     */
    public void forgetWorld(final World world) {
        lastPosition.values().removeIf(location -> location.getWorld() == world);
    }

    /**
     * One polled sample for a player. Compares the current block against the last sampled one without
     * allocating, and only on a change resolves regions and runs the crossing. A denied crossing is
     * undone by teleporting back to the last accepted position — the task path has no event to cancel
     * — and the stored position is left untouched so the player cannot creep forward one interval at
     * a time.
     *
     * <p>On a sweep the region set is resolved even when the player has not moved, so anyone standing
     * inside a region that now refuses them is removed. That covers logging in inside one, a region
     * being created or its entry flag flipped around them, and any teleport another plugin performed
     * that landed them past the boundary between polls.
     */
    private void poll(final Player player, final boolean sweep) {
        final UUID uuid = player.getUniqueId();
        final Location last = lastPosition.get(uuid);
        final boolean sameBlock = last != null
            && last.getWorld() == player.getWorld()
            && last.getBlockX() == Location.locToBlock(player.getX())
            && last.getBlockY() == Location.locToBlock(player.getY())
            && last.getBlockZ() == Location.locToBlock(player.getZ());
        if (sameBlock && !sweep) {
            return;
        }
        if (EventGate.disabled(player.getWorld(), "PlayerMoveEvent")) {
            return;
        }

        if (sameBlock) {
            final ApplicableRegionSet here = query.getApplicableRegions(player);
            if (deniedInside(player, uuid, here)) {
                eject(player, uuid, last, here);
            } else {
                applyState(player, here);
            }
            return;
        }

        final Location current = player.getLocation();
        final ApplicableRegionSet toSet = query.getApplicableRegions(current);

        if (last == null || last.getWorld() != current.getWorld()) {
            if (deniedInside(player, uuid, toSet)) {
                eject(player, uuid, last, toSet);
                return;
            }
            lastPosition.put(uuid, current);
            applyState(player, toSet);
            return;
        }

        final ApplicableRegionSet fromSet = query.getApplicableRegions(last);
        if (processCrossing(player, current.getWorld(), fromSet, toSet)) {
            player.teleportAsync(last);
            return;
        }
        if (SessionDispatch.ACTIVE
            && SessionDispatch.testMove(player, last, current, SessionDispatch.selfMove(player)) != null) {
            player.teleportAsync(last);
            return;
        }
        lastPosition.put(uuid, current);
        applyState(player, toSet);
    }

    /**
     * Whether the player is standing somewhere the entry flag refuses them. Cheapest checks first:
     * wilderness and bypassing staff leave immediately, before any flag resolution.
     *
     * <p>Deliberately does not consider {@code player-count-limit}: that is an entry gate, not a
     * standing condition. Applied here, a region holding more players than its limit would look full
     * to every one of them at once and the sweep would eject the lot.
     */
    private boolean deniedInside(final Player player, final UUID uuid, final ApplicableRegionSet set) {
        if (set.isEmpty() || Bypass.has(player)) {
            return false;
        }
        return !set.testState(Flags.ENTRY, uuid) && !isMember(set, uuid);
    }

    /**
     * Removes a player from a region that refuses them, preferring the last position they were
     * accepted at and falling back to world spawn. The fallback is re-checked, because a region whose
     * entry flag changed while someone stood in it makes their last accepted position denied too —
     * teleporting them back there would leave them stuck, re-ejected every sweep.
     */
    private void eject(
        final Player player, final UUID uuid, final @Nullable Location last, final ApplicableRegionSet set
    ) {
        Location target = last;
        if (target == null || deniedInside(player, uuid, query.getApplicableRegions(target))) {
            target = player.getWorld().getSpawnLocation();
        }
        dismountIfRiding(player);
        lastPosition.put(uuid, target);
        messages.sendFlag(player, set.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
        player.teleportAsync(target);
    }

    /**
     * Entry/exit enforcement and per-region enter/leave effects for a player who moved from
     * {@code fromSet} to {@code toSet}. Returns true if the crossing was denied, leaving it to the
     * caller to undo the movement — the event path cancels, the polled path teleports back.
     *
     * <p>{@code world} is the destination's, needed to count a region's occupants: regions carry no
     * world of their own, and on a cross-world teleport the player's is still the origin.
     */
    private boolean processCrossing(
        final Player player, final World world, final ApplicableRegionSet fromSet,
        final ApplicableRegionSet toSet
    ) {
        final boolean entering = !isInside(fromSet, toSet);
        final boolean leaving = !isInside(toSet, fromSet);
        if (!entering && !leaving) {
            return false;
        }

        final UUID uuid = player.getUniqueId();
        final boolean bypass = Bypass.has(player);

        if (!bypass && entering && !toSet.testState(Flags.ENTRY, uuid) && !isMember(toSet, uuid)) {
            dismountIfRiding(player);
            messages.sendFlag(player, toSet.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
            return true;
        }

        if (!bypass && leaving && !fromSet.testState(Flags.EXIT, uuid) && !isMember(fromSet, uuid)
            && !Boolean.TRUE.equals(fromSet.queryValue(Flags.EXIT_OVERRIDE))) {
            dismountIfRiding(player);
            messages.sendFlag(player, fromSet.queryValue(Flags.EXIT_DENY_MESSAGE), "exit-denied");
            return true;
        }
        if (!bypass && entering && levelsInUse(toSet) && !isMember(toSet, uuid)
            && levelDenied(player, toSet)) {
            messages.send(player, "entry-denied");
            return true;
        }
        if (!bypass && entering && toSet.worldUses(Flags.PLAYER_COUNT_LIMIT)
            && countDenied(world, player, uuid, fromSet, toSet)) {
            return true;
        }

        if (entering) {
            for (int i = 0, n = toSet.size(); i < n; i++) {
                final ProtectedRegion region = toSet.get(i);
                if (!contains(fromSet, region)) {
                    onEnterRegion(player, region);
                }
            }
        }
        if (leaving) {
            for (int i = 0, n = fromSet.size(); i < n; i++) {
                final ProtectedRegion region = fromSet.get(i);
                if (!contains(toSet, region)) {
                    onLeaveRegion(player, region);
                }
            }
        }
        return false;
    }

    /**
     * Entry/exit enforcement for a living mount (pig, horse, strider) carrying a rider whose crossing
     * is not the one {@link #onMove} sees (the driver's crossing is, via the move-vehicle packet).
     * {@link #deniedCrossing} ejects and teleports the denied rider out — a mounted player is glued to
     * the vehicle, so a cancel alone does not hold them; the cancel here is just a cheap first stop.
     * The {@code riddenMounts} fast-path keeps this near-free when nobody is riding anything.
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
        if (EventGate.disabled(event)) {
            return;
        }
        if (deniedCrossing(mount, event.getFrom(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    /**
     * Same enforcement for boats and minecarts, which fire {@link VehicleMoveEvent} rather than
     * {@link EntityMoveEvent}. {@link #deniedCrossing} ejects the denied rider and teleports them out.
     * Vehicles register in {@code riddenMounts} on {@link EntityMountEvent} just like living mounts,
     * so the guard skips the work for driverless boats/minecarts, which fire this event just as often.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(final VehicleMoveEvent event) {
        if (riddenMounts.isEmpty() || !riddenMounts.contains(event.getVehicle().getUniqueId())) {
            return;
        }
        if (EventGate.disabled(event)) {
            return;
        }
        deniedCrossing(event.getVehicle(), event.getFrom(), event.getTo());
    }

    /**
     * Shared entry/exit check for a moving vehicle/mount: every non-bypassing player passenger that
     * would cross a region boundary it may not is ejected and teleported back to {@code from}. A
     * mounted player is glued to the client-driven vehicle, so a plain cancel or a vehicle teleport
     * does not hold them — dismounting does. Returns true if any rider was denied (so the mount path
     * can also cancel its move). Cheap guards first: same-block and no-boundary-change exits before
     * any membership work.
     */
    private boolean deniedCrossing(final Entity vehicle, final Location from, final Location to) {
        if (from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return false;
        }
        final List<Entity> passengers = vehicle.getPassengers();
        if (passengers.isEmpty()) {
            riddenMounts.remove(vehicle.getUniqueId());
            return false;
        }

        final ApplicableRegionSet fromSet = query.getApplicableRegions(from);
        final ApplicableRegionSet toSet = query.getApplicableRegions(to);
        final boolean entering = !isInside(fromSet, toSet);
        final boolean leaving = !isInside(toSet, fromSet);
        if (!entering && !leaving) {
            return false;
        }

        final boolean limited = entering && toSet.worldUses(Flags.PLAYER_COUNT_LIMIT);
        final boolean levelled = entering && levelsInUse(toSet);
        final boolean sessions = SessionDispatch.ACTIVE;
        final boolean exitGuarded = leaving && !Boolean.TRUE.equals(fromSet.queryValue(Flags.EXIT_OVERRIDE));
        final World world = to.getWorld();

        List<Player> denied = null;
        for (final Entity passenger : passengers) {
            if (!(passenger instanceof Player player) || Bypass.has(player)) {
                continue;
            }
            final UUID uuid = player.getUniqueId();
            if (entering && !toSet.testState(Flags.ENTRY, uuid) && !isMember(toSet, uuid)) {
                messages.sendFlag(player, toSet.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
            } else if (exitGuarded && !fromSet.testState(Flags.EXIT, uuid) && !isMember(fromSet, uuid)) {
                messages.sendFlag(player, fromSet.queryValue(Flags.EXIT_DENY_MESSAGE), "exit-denied");
            } else if (levelled && !isMember(toSet, uuid) && levelDenied(player, toSet)) {
                messages.send(player, "entry-denied");
            } else if ((!limited || !countDenied(world, player, uuid, fromSet, toSet))
                && (!sessions
                || SessionDispatch.testMove(player, from, to, SessionDispatch.Move.RIDE) == null)) {
                continue;
            }
            if (denied == null) {
                denied = new ArrayList<>(1);
            }
            denied.add(player);
        }
        if (denied == null) {
            return false;
        }
        for (final Player rider : denied) {
            rider.leaveVehicle();
            rider.teleportAsync(from);
        }
        return true;
    }

    /**
     * A cancelled {@link PlayerMoveEvent} reverts an on-foot player, but a mounted one is glued to
     * its vehicle — which the client drives — so the server's revert just snaps them back onto it.
     * Dismounting first lets the cancel's position revert actually hold. The driver of a mount/vehicle
     * produces this event through the move-vehicle packet, so this is the path that catches riding in.
     */
    private static void dismountIfRiding(final Player player) {
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
    }

    /**
     * Blocks mounting a vehicle that sits in a region the player may not enter (otherwise a player
     * could mount an animal standing inside a no-entry region and be carried in), and starts
     * tracking the mount so {@link #onMountMove} enforces its movement.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(final EntityMountEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final Entity mount = event.getMount();
        if (!Bypass.has(player)) {
            final ApplicableRegionSet set = query.getApplicableRegions(mount);
            if (!set.testState(Flags.ENTRY, player.getUniqueId()) && !isMember(set, player.getUniqueId())) {
                event.setCancelled(true);
                messages.sendFlag(player, set.queryValue(Flags.ENTRY_DENY_MESSAGE), "entry-denied");
                return;
            }
        }
        if (SessionDispatch.ACTIVE) {
            final Location seat = mount.getLocation();
            if (SessionDispatch.testMove(player, player.getLocation(), seat,
                SessionDispatch.Move.EMBARK) != null) {
                event.setCancelled(true);
                return;
            }
        }
        riddenMounts.add(mount.getUniqueId());
    }

    /**
     * Stops tracking a mount once its last player rider steps off. The event fires before the
     * dismount applies, so the leaver is still listed as a passenger and has to be excluded —
     * untracking on the first of two riders would silently stop enforcing the crossing for the one
     * still aboard. Cancelled dismounts leave the tracking alone, since the rider stays on.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(final EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player leaver)) {
            return;
        }
        final Entity mount = event.getDismounted();
        for (final Entity passenger : mount.getPassengers()) {
            if (passenger != leaver && passenger instanceof Player) {
                return;
            }
        }
        riddenMounts.remove(mount.getUniqueId());
    }

    /**
     * Announces a crossing to other plugins, allocating the event only when something is listening.
     *
     * <p>This runs per region crossed on a movement path, so the empty case has to cost nothing:
     * {@code callEvent} on a handler list with no listeners still does work, and the event object
     * itself is the allocation worth avoiding on a server where no plugin uses these at all.
     */
    private static void fireEnter(final Player player, final ProtectedRegion region) {
        if (RegionEnterEvent.getHandlerList().getRegisteredListeners().length != 0) {
            Bukkit.getPluginManager().callEvent(new RegionEnterEvent(player, region));
        }
    }

    private static void fireExit(final Player player, final ProtectedRegion region) {
        if (RegionExitEvent.getHandlerList().getRegisteredListeners().length != 0) {
            Bukkit.getPluginManager().callEvent(new RegionExitEvent(player, region));
        }
    }

    private void onEnterRegion(final Player player, final ProtectedRegion region) {
        fireEnter(player, region);
        final String greeting = region.getFlag(Flags.GREETING);
        if (greeting != null) {
            player.sendMessage(messages.render(greeting, player));
        }
        showTitle(player, region.getFlag(Flags.GREETING_TITLE));
        notifyStaff(player, region, Flags.NOTIFY_ENTER, "notify-enter");
        runCommand(player, region.getFlag(Flags.COMMAND_ON_ENTRY), false);
        runCommand(player, region.getFlag(Flags.CONSOLE_COMMAND_ON_ENTRY), true);
        playSound(player, region.getFlag(Flags.PLAY_SOUNDS));
        teleport(player, region.getFlag(Flags.TELEPORT_ON_ENTRY));
        if (region.getFlag(Flags.CHAMBERED_ENDERPEARL) == State.DENY) {
            pearls.removeFor(player.getUniqueId());
        }
    }

    private void onLeaveRegion(final Player player, final ProtectedRegion region) {
        fireExit(player, region);
        final String farewell = region.getFlag(Flags.FAREWELL);
        if (farewell != null) {
            player.sendMessage(messages.render(farewell, player));
        }
        showTitle(player, region.getFlag(Flags.FAREWELL_TITLE));
        notifyStaff(player, region, Flags.NOTIFY_LEAVE, "notify-leave");
        runCommand(player, region.getFlag(Flags.COMMAND_ON_EXIT), false);
        runCommand(player, region.getFlag(Flags.CONSOLE_COMMAND_ON_EXIT), true);
        teleport(player, region.getFlag(Flags.TELEPORT_ON_EXIT));
    }

    /**
     * Announces a crossing to staff holding {@code uworldguard.notify}, wording it from the
     * {@code messageKey} entry in messages.yml — so the announcement can be recoloured, reworded or
     * turned off without touching the flag that triggers it.
     *
     * <p>The broadcast goes to the global region thread rather than running here: it walks every
     * online player, which is not work to do inside the move event of the one player who crossed.
     * Only the crossing player's name is carried across, never the player itself, since that thread
     * does not own them. The recipients are not owned by it either — a player's permission map is
     * rewritten unsynchronised on their own thread — so the permission test and the send hop once
     * more, onto each recipient's entity scheduler.
     */
    private void notifyStaff(
        final Player player, final ProtectedRegion region, final BooleanFlag flag, final String messageKey
    ) {
        if (!Boolean.TRUE.equals(region.getFlag(flag))) {
            return;
        }
        final String name = player.getName();
        final String id = region.getId();
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            final Component line = messages.broadcast(messageKey,
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("region", id));
            if (line == null) {
                return;
            }
            for (final Player staff : plugin.getServer().getOnlinePlayers()) {
                staff.getScheduler().run(plugin, task -> {
                    if (staff.hasPermission(NOTIFY_PERMISSION)) {
                        staff.sendMessage(line);
                    }
                }, null);
            }
        });
    }

    /**
     * Shows a greeting/farewell title. A literal {@code \n} splits it into title and subtitle, which is
     * how WorldGuard writes a two-line title into a region file.
     */
    private void showTitle(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final int split = raw.indexOf("\\n");
        final String main = split < 0 ? raw : raw.substring(0, split);
        final String sub = split < 0 ? "" : raw.substring(split + 2);
        player.showTitle(Title.title(
            messages.render(main, player),
            sub.isEmpty() ? Component.empty() : messages.render(sub, player)));
    }

    /**
     * Dispatches an entry/exit command flag. Neither variant runs inline: a console command may touch
     * any world or player, so it goes to the global region thread, and a player command goes to that
     * player's own scheduler. Besides being the Folia-correct owning thread in each case, this keeps
     * an arbitrary command out of the middle of the move event we are still handling.
     */
    private void runCommand(final Player player, final @Nullable String raw, final boolean console) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final String command = messages.expand(player, raw.replace("%player%", player.getName()));
        if (console) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } else {
            player.getScheduler().run(plugin, _ -> Bukkit.dispatchCommand(player, command), null);
        }
    }

    /**
     * Plays a {@code play-sounds} value: a sound key, optionally followed by {@code :volume} and then
     * {@code :pitch}. Read from the right, because the key is itself namespaced — splitting on the
     * first colon read {@code minecraft:block.anvil.land} as the sound "minecraft" and silently
     * played nothing.
     */
    private void playSound(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        int end = raw.length();
        float volume = 1f;
        float pitch = 1f;
        final int last = numberStart(raw, end);
        if (last >= 0) {
            final int previous = numberStart(raw, last);
            if (previous >= 0) {
                volume = Float.parseFloat(raw.substring(previous + 1, last));
                pitch = Float.parseFloat(raw.substring(last + 1, end));
                end = previous;
            } else {
                volume = Float.parseFloat(raw.substring(last + 1, end));
                end = last;
            }
        }
        player.playSound(player, raw.substring(0, end).trim(), volume, pitch);
    }

    /**
     * Index of the {@code ':'} introducing the numeric segment that ends at {@code end}, or -1 when
     * that segment is not a plain number — which is what the path half of a namespaced key looks
     * like. Scans rather than splits, so a value with no volume or pitch allocates nothing beyond the
     * key itself.
     */
    private static int numberStart(final String raw, final int end) {
        final int colon = raw.lastIndexOf(':', end - 1);
        if (colon <= 0 || colon + 1 == end) {
            return -1;
        }
        boolean digit = false;
        boolean dot = false;
        for (int i = colon + 1; i < end; i++) {
            final char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == '.' && !dot) {
                dot = true;
            } else {
                return -1;
            }
        }
        return digit ? colon : -1;
    }

    /**
     * Teleports the moving player on their own entity scheduler (next tick), which is Folia-safe
     * and avoids re-entering the move event we are currently handling. The destination comes from a
     * flag and may name any world, so it is never guaranteed to belong to the region we are on —
     * {@code teleportAsync} is the only form that can cross one.
     */
    private void teleport(final Player player, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        final Location target = Locations.parse(messages.expand(player, raw));
        if (target != null) {
            player.getScheduler().run(plugin, task -> player.teleportAsync(target), null);
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

    /**
     * Whether any region the player is newly entering is already holding its {@code player-count-limit}.
     * Members and owners are never refused, but they do count towards the total, so a region full of
     * its own members is closed to outsiders. Sends the refusal itself, because the message carries
     * the count and the limit it just resolved.
     *
     * <p>A limit belongs to one region rather than to the set, so this walks the regions being entered
     * instead of using {@code queryValue}, which would collapse several nested limits into whichever
     * has the highest priority. The global region is not consulted for the same reason it is not in
     * the set: a per-world cap on players is the server's slot limit, not a region flag.
     */
    private boolean countDenied(
        final World world, final Player player, final UUID uuid, final ApplicableRegionSet fromSet,
        final ApplicableRegionSet toSet
    ) {
        for (int i = 0, n = toSet.size(); i < n; i++) {
            final ProtectedRegion region = toSet.get(i);
            if (contains(fromSet, region) || region.isMember(uuid)) {
                continue;
            }
            final Integer limit = region.getFlag(Flags.PLAYER_COUNT_LIMIT);
            if (limit == null) {
                continue;
            }
            final int count = occupants(world, region, player);
            if (count >= limit) {
                messages.send(player, "region-full",
                    Placeholder.unparsed("count", Integer.toString(count)),
                    Placeholder.unparsed("limit", Integer.toString(limit)));
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the world this set came from sets an entry level threshold anywhere. Two bitset tests,
     * so a crossing on the overwhelming majority of servers never resolves either flag.
     */
    private static boolean levelsInUse(final ApplicableRegionSet toSet) {
        return toSet.worldUses(Flags.ENTRY_MIN_LEVEL) || toSet.worldUses(Flags.ENTRY_MAX_LEVEL);
    }

    /**
     * How many players stand inside {@code region} right now, excluding the one moving: on the polled
     * path they have already physically entered, so counting them would read one over the truth.
     *
     * <p>Only reached once the world's flag bitset says someone uses the limit and the player is
     * actually entering a region that sets it, so the walk over the world's players stays off every
     * ordinary crossing.
     *
     * <p>The other players' positions are read from the mover's thread, so a count can be a player
     * out of date if someone crossed the boundary in the same tick. Accepted: hopping to each
     * player's own scheduler to read three doubles would cost more than the imprecision, and a
     * capacity check that is momentarily off by one settles on the next crossing.
     */
    private static int occupants(final World world, final ProtectedRegion region, final Player mover) {
        int count = 0;
        for (final Player other : world.getPlayers()) {
            if (other == mover) {
                continue;
            }
            if (region.contains(Location.locToBlock(other.getX()), Location.locToBlock(other.getY()),
                Location.locToBlock(other.getZ()))) {
                count++;
            }
        }
        return count;
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

    /**
     * Applies the region's continuous player state (game-mode, walk/fly speed, flight) while inside,
     * restoring each to the value the player had before entering once the override no longer applies.
     *
     * <p>Every restore branch is gated on its map being non-empty. The read side already skips the
     * work via {@code worldUses}, but the restore side ran unconditionally — six map mutations against
     * permanently empty maps on every block crossing of every player, on a server where no region sets
     * any of these flags.
     */
    private void applyState(final Player player, final ApplicableRegionSet toSet) {
        final UUID uuid = player.getUniqueId();
        boolean owedChanged = false;

        final GameMode mode = toSet.worldUses(Flags.GAME_MODE)
            ? parseGameMode(toSet.queryValue(Flags.GAME_MODE)) : null;
        if (mode != null) {
            if (player.getGameMode() != mode) {
                owedChanged |= savedGameModes.putIfAbsent(uuid, player.getGameMode()) == null;
                player.setGameMode(mode);
            }
        } else if (!savedGameModes.isEmpty()) {
            final GameMode saved = savedGameModes.remove(uuid);
            if (saved != null) {
                owedChanged = true;
                if (player.getGameMode() != saved) {
                    player.setGameMode(saved);
                }
            }
        }

        final Double walk = toSet.worldUses(Flags.WALK_SPEED) ? toSet.queryValue(Flags.WALK_SPEED) : null;
        if (walk != null) {
            if (savedWalkSpeed.get(uuid) == null) {
                owedChanged |= savedWalkSpeed.putIfAbsent(uuid, player.getWalkSpeed()) == null;
            }
            final float target = clampSpeed(walk.floatValue());
            if (player.getWalkSpeed() != target) {
                player.setWalkSpeed(target);
            }
        } else if (!savedWalkSpeed.isEmpty()) {
            final Float saved = savedWalkSpeed.remove(uuid);
            if (saved != null) {
                owedChanged = true;
                player.setWalkSpeed(saved);
            }
        }

        final Double fly = toSet.worldUses(Flags.FLY_SPEED) ? toSet.queryValue(Flags.FLY_SPEED) : null;
        if (fly != null) {
            if (savedFlySpeed.get(uuid) == null) {
                owedChanged |= savedFlySpeed.putIfAbsent(uuid, player.getFlySpeed()) == null;
            }
            final float target = clampSpeed(fly.floatValue());
            if (player.getFlySpeed() != target) {
                player.setFlySpeed(target);
            }
        } else if (!savedFlySpeed.isEmpty()) {
            final Float saved = savedFlySpeed.remove(uuid);
            if (saved != null) {
                owedChanged = true;
                player.setFlySpeed(saved);
            }
        }

        final Boolean allowFly = toSet.worldUses(Flags.FLY) ? toSet.queryValue(Flags.FLY) : null;
        if (allowFly != null) {
            if (player.getAllowFlight() != allowFly) {
                owedChanged |= savedAllowFlight.putIfAbsent(uuid, player.getAllowFlight()) == null;
                player.setAllowFlight(allowFly);
                if (!allowFly && player.isFlying()) {
                    player.setFlying(false);
                }
            }
        } else if (!savedAllowFlight.isEmpty()) {
            final Boolean saved = savedAllowFlight.remove(uuid);
            if (saved != null) {
                owedChanged = true;
                if (player.getAllowFlight() != saved) {
                    player.setAllowFlight(saved);
                }
            }
        }

        if (owedChanged) {
            restores.record(uuid, savedGameModes.get(uuid), savedWalkSpeed.get(uuid),
                savedFlySpeed.get(uuid), savedAllowFlight.get(uuid));
        }

        collision.set(player, toSet.worldUses(Flags.DISABLE_COLLISION)
            && Boolean.TRUE.equals(toSet.queryValue(Flags.DISABLE_COLLISION)));

        if (toSet.worldUses(Flags.CHAT_PREFIX) || toSet.worldUses(Flags.CHAT_SUFFIX)) {
            chatTags.setPrefix(uuid, toSet.queryValue(Flags.CHAT_PREFIX));
            chatTags.setSuffix(uuid, toSet.queryValue(Flags.CHAT_SUFFIX));
        }

        if (toSet.worldUses(Flags.SEND_CHAT) || toSet.worldUses(Flags.RECEIVE_CHAT)) {
            final boolean bypass = Bypass.has(player);
            chatTags.setMuted(uuid, !bypass && !toSet.testState(Flags.SEND_CHAT, uuid));
            chatTags.setDeafened(uuid, !bypass && !toSet.testState(Flags.RECEIVE_CHAT, uuid));
        }

        applyHidePlayers(player, uuid, toSet.worldUses(Flags.HIDE_PLAYERS)
            && Boolean.TRUE.equals(toSet.queryValue(Flags.HIDE_PLAYERS)));
    }

    /**
     * Hides every other online player from {@code player} while inside a hide-players region, and
     * restores them on leaving. State-transition based: the O(n) hide/show loop only runs when the
     * player crosses into or out of the hidden state, never per move.
     *
     * <p>Both calls mutate the <em>viewer's</em> own hidden-entity map, so the whole loop runs on the
     * viewer's scheduler. Fanning out to each target's thread instead would have N region threads
     * writing one plain {@code HashMap} at once — lost entries, or a resize that spins a thread.
     * The {@code hidden} flip stays out here: it is a concurrent set and already atomic.
     */
    private void applyHidePlayers(final Player player, final UUID uuid, final boolean shouldHide) {
        if (shouldHide) {
            if (hidden.add(uuid)) {
                player.getScheduler().run(plugin, _ -> {
                    for (final Player other : Bukkit.getOnlinePlayers()) {
                        if (other != player) {
                            player.hidePlayer(plugin, other);
                        }
                    }
                }, null);
            }
        } else if (!hidden.isEmpty() && hidden.remove(uuid)) {
            player.getScheduler().run(plugin, _ -> {
                for (final Player other : Bukkit.getOnlinePlayers()) {
                    if (other != player) {
                        player.showPlayer(plugin, other);
                    }
                }
            }, null);
        }
    }

    /**
     * On login, teleports the player out if they log in where the join-location flag is set, and
     * hides the newcomer from anyone currently inside a hide-players region so the late join does not
     * leak into their view. Both run on the relevant player's own scheduler for Folia safety.
     */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player joiner = event.getPlayer();

        if (taskMode) {
            startPoll(joiner);
        }
        replayPendingRestore(joiner);
        if (SessionDispatch.ACTIVE) {
            SessionDispatch.initialize(joiner);
        }
        if (!hidden.isEmpty()) {
            for (final UUID viewerId : hidden) {
                final Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && viewer != joiner) {
                    viewer.getScheduler().run(plugin, task -> viewer.hidePlayer(plugin, joiner), null);
                }
            }
        }

        final String raw = query.queryValue(joiner, Flags.JOIN_LOCATION);
        if (raw != null && !raw.isBlank()) {
            final Location target = Locations.parse(messages.expand(joiner, raw));
            if (target != null) {
                joiner.getScheduler().run(plugin, task -> joiner.teleportAsync(target), null);
            }
        }
    }

    /**
     * Replays outstanding restores for players who are already online, which only happens when the
     * plugin was disabled and enabled again without anyone disconnecting. No join event is coming for
     * them, so without this their override waits for a relog. Empty on an ordinary boot, where nobody
     * is online yet.
     *
     * <p>Unlike the disable path this may schedule: the plugin is enabled here, so an entity task
     * genuinely runs.
     */
    public void replayOnlineRestores() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, _ -> replayPendingRestore(player), null);
        }
    }

    /**
     * Puts back an override a previous shutdown could not undo itself, for a player who was standing
     * in the region at the time. The join event runs on the joiner's own thread, which is the one
     * allowed to make the change.
     *
     * <p>Runs before anything else in the handler, so if they have logged straight back into the same
     * region {@link #applyState} saves the value they actually started with rather than the override
     * still on them.
     */
    private void replayPendingRestore(final Player player) {
        final PendingRestores.State owed = restores.take(player.getUniqueId());
        if (owed != null) {
            apply(player, owed);
        }
    }

    /**
     * Puts one player's owed state back. Only ever called on that player's own thread — from their
     * join, or from the disable for the players the disabling region owns.
     */
    private static void apply(final Player player, final PendingRestores.State owed) {
        if (owed.gameMode() != null) {
            player.setGameMode(owed.gameMode());
        }
        if (owed.walkSpeed() != null) {
            player.setWalkSpeed(owed.walkSpeed());
        }
        if (owed.flySpeed() != null) {
            player.setFlySpeed(owed.flySpeed());
        }
        if (owed.allowFlight() != null && !owed.allowFlight()) {
            player.setAllowFlight(false);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        stopPoll(uuid);
        if (SessionDispatch.TRACKING) {
            SessionDispatch.uninitialize(player);
        }
        SessionDispatch.forget(player);
        messages.clear(uuid);
        collision.set(player, false);
        pearls.clear(uuid);
        chatTags.clear(uuid);
        hidden.remove(uuid);
        lastPosition.remove(uuid);
        Bypass.clear(uuid);

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
        restores.forget(uuid);
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

    /**
     * Whether {@code region} is in {@code set}, by identity. Both sets in a crossing come from the
     * same world's manager, so a shared region is the same object — no id hashing, no set allocation
     * on the crossing path. Across a world change the objects differ even for equal names, which is
     * what we want: every region of the old world is left and every region of the new one entered.
     */
    private static boolean contains(final ApplicableRegionSet set, final ProtectedRegion region) {
        for (int i = 0, n = set.size(); i < n; i++) {
            if (set.get(i) == region) {
                return true;
            }
        }
        return false;
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
     * True if every region in {@code inner} also appears in {@code outer} (no new boundary crossed).
     * Both sets come from the same world's manager, so a shared region is the same object — the small
     * lists are compared by identity directly, allocating nothing on the per-move hot path.
     */
    private static boolean isInside(final ApplicableRegionSet outer, final ApplicableRegionSet inner) {
        for (int i = 0, n = inner.size(); i < n; i++) {
            final ProtectedRegion region = inner.get(i);
            boolean matched = false;
            for (int j = 0, m = outer.size(); j < m; j++) {
                if (outer.get(j) == region) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }
}
