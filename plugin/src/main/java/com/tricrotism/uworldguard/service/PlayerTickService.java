package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Applies the once-a-second player flags: heal-amount / heal-min-health / heal-max-health, and
 * give-effects / blocked-effects. Both used to be separate tasks, each iterating every online player
 * and each resolving that player's regions; merging them halves the scheduler churn and does one
 * region lookup per player per second instead of two.
 *
 * <p>Folia-correct: each player carries their own repeating task on their own entity scheduler, so
 * health and potion API are only ever touched on the entity's region thread. Region flag reads go
 * through the thread-safe {@link RegionQuery}. The tick is skipped when no region on the server uses
 * any of these flags, and each half is skipped per-world via
 * {@link ApplicableRegionSet#worldUses}, except that a player still holding effects or a sky lock
 * this service applied keeps ticking that half until it is undone, so changing world or deleting the
 * last region using the flag cannot strand it.
 *
 * <p>In {@code movement.mode: TASK} the same task also runs the movement poll, so a player carries one
 * repeating task whatever the configuration. Each second it also tells {@link ChunkUnloadService}
 * which chunk the player is in.
 */
@NullMarked
public final class PlayerTickService implements Listener {

    public static final String NOTIFY_PERMISSION = "uworldguard.notify";
    private static final int TICKS_PER_SECOND = 20;
    private static final int REAPPLY_TICKS = 300;
    /**
     * Seconds between refreshes of an unchanged effect set. Comfortably inside {@link #REAPPLY_TICKS}
     * so an effect never lapses, and far enough apart that a player standing in an effect region is
     * not handed the same potion — and its client packet — once a second for nothing.
     */
    private static final long REAPPLY_SECONDS = 10L;

    /**
     * What this service last gave {@code player}: the flag value it came from, so an unchanged set is
     * recognized by identity, the types it applied, so walking out strips exactly those, and the
     * effects it built, which the periodic refresh re-sends as they are.
     */
    private record Granted(Set<PotionEffect> source, Set<PotionEffectType> types, List<PotionEffect> applied) {}

    /**
     * The time and weather overrides last pushed to a player, as the raw flag values. Held so the
     * per-second tick can tell "nothing to do" from "something changed": without it, one region
     * anywhere in a world setting either flag made every player in that world reset their sky every
     * second forever, including everyone who never goes near it.
     */
    private record SkyLock(@Nullable String time, @Nullable String weather) {}

    /**
     * The movement poll a player's task runs every {@code ticks} ticks, passing true on every
     * {@code sweepEvery}th run.
     */
    public record Poll(BiConsumer<Player, Boolean> run, int ticks, int sweepEvery) {}

    /**
     * A player's task and its count of seconds that did work. The count outlives a restart, so a
     * reload that retunes the poll does not push back the next heal, feed or effect refresh.
     */
    private record Tick(ScheduledTask task, AtomicLong seconds) {}

    private final Plugin plugin;
    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final ChunkUnloadService chunks;
    private final Map<UUID, Granted> granted = new ConcurrentHashMap<>();
    private final Map<UUID, SkyLock> skyLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Tick> tasks = new ConcurrentHashMap<>();

    /**
     * Online holders of {@code permission}, re-tested each second while {@code active} says the
     * permission can matter.
     */
    private record Watch(String permission, BooleanSupplier active, Set<UUID> holders) {}

    private volatile Watch[] watches = new Watch[0];
    private final Set<UUID> regionStaff;
    private volatile @Nullable Poll poll;

    public PlayerTickService(
        final Plugin plugin,
        final RegionContainerImpl container,
        final RegionQuery query,
        final ChunkUnloadService chunks
    ) {
        this.plugin = plugin;
        this.container = container;
        this.query = query;
        this.chunks = chunks;
        this.regionStaff = watch(NOTIFY_PERMISSION,
            () -> container.anyRegionUses(Flags.NOTIFY_ENTER) || container.anyRegionUses(Flags.NOTIFY_LEAVE));
    }

    /**
     * Tracks which online players hold {@code permission}, so a broadcast to them hops to those
     * players instead of to everyone online. Each player is re-tested on their own tick once a second
     * and at join, only while {@code active} returns true. Register before players' ticks start.
     *
     * @return a read-only live view of the holders, at most a second old, safe to iterate anywhere
     */
    public Set<UUID> watch(final String permission, final BooleanSupplier active) {
        final Set<UUID> holders = ConcurrentHashMap.newKeySet();
        final Watch[] current = watches;
        final Watch[] grown = Arrays.copyOf(current, current.length + 1);
        grown[current.length] = new Watch(permission, active, holders);
        watches = grown;
        return Collections.unmodifiableSet(holders);
    }

    /**
     * (Re)starts every online player's task, carrying {@code poll} when it is not null. Players who
     * join later pick up the same poll.
     *
     * @param poll the movement poll, or null when movement is event driven
     */
    public void start(final @Nullable Poll poll) {
        this.poll = poll;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            startTick(player);
        }
    }

    /**
     * Schedules {@code player}'s tick, replacing any they already had.
     *
     * <p>One repeating task per player on their own entity scheduler, rather than a global task that
     * scheduled one entity task per player per second: that shape allocated and queued a task object
     * for every player every second, through the one thread the whole server shares, to do work that
     * only ever touches one player.
     *
     * <p>With a poll, the task runs every gcd(poll interval, 20) ticks, so the poll and the per-second
     * work each land on exactly the tick they would with tasks of their own. The replaced task is
     * cancelled only after the new handle is stored, so two overlapping starts cannot orphan one.
     */
    private void startTick(final Player player) {
        final UUID uuid = player.getUniqueId();
        final Poll moves = this.poll;
        final long period = moves == null ? TICKS_PER_SECOND : gcd(moves.ticks(), TICKS_PER_SECOND);
        final AtomicLong elapsed = new AtomicLong();
        final Tick current = tasks.get(uuid);
        final AtomicLong seconds = current == null ? new AtomicLong() : current.seconds();
        final ScheduledTask scheduled = player.getScheduler().runAtFixedRate(plugin, _ -> {
            final long now = elapsed.addAndGet(period);
            if (moves != null && now % moves.ticks() == 0L) {
                moves.run().accept(player, (now / moves.ticks()) % moves.sweepEvery() == 0L);
            }
            if (now % TICKS_PER_SECOND == 0L) {
                second(player, uuid, seconds);
            }
        }, null, period, period);
        if (scheduled != null) {
            final Tick replaced = tasks.put(uuid, new Tick(scheduled, seconds));
            if (replaced != null) {
                replaced.task().cancel();
            }
        }
    }

    /**
     * The online players last seen holding {@link #NOTIFY_PERMISSION}, kept while some region uses
     * {@code notify-enter} or {@code notify-leave}.
     *
     * @return a read-only live view, at most a second old, safe to iterate from any thread
     */
    public Set<UUID> notifyStaff() {
        return regionStaff;
    }

    private void noteStaff(final Player player, final UUID uuid) {
        for (final Watch watch : watches) {
            if (!watch.active().getAsBoolean()) {
                continue;
            }
            if (player.hasPermission(watch.permission())) {
                watch.holders().add(uuid);
            } else {
                watch.holders().remove(uuid);
            }
        }
    }

    private void second(final Player player, final UUID uuid, final AtomicLong seconds) {
        chunks.visit(player);
        noteStaff(player, uuid);
        final boolean sessions = SessionDispatch.ACTIVE;
        if (!sessions
            && !container.anyRegionUses(Flags.HEAL_AMOUNT)
            && !container.anyRegionUses(Flags.FEED_AMOUNT)
            && !container.anyRegionUses(Flags.TIME_LOCK)
            && !container.anyRegionUses(Flags.WEATHER_LOCK)
            && !container.anyRegionUses(Flags.GIVE_EFFECTS)
            && !container.anyRegionUses(Flags.BLOCKED_EFFECTS)
            && !skyLocks.containsKey(uuid)
            && !granted.containsKey(uuid)) {
            return;
        }
        apply(player, seconds.incrementAndGet(), sessions);
    }

    static int gcd(final int a, final int b) {
        int x = a;
        int y = b;
        while (y != 0) {
            final int r = x % y;
            x = y;
            y = r;
        }
        return x;
    }

    private void stopTick(final UUID uuid) {
        final Tick existing = tasks.remove(uuid);
        if (existing != null) {
            existing.task().cancel();
        }
    }

    /**
     * Cancels every tick. Paper drops a plugin's tasks on disable anyway, but holding the handles
     * means the service can be stopped without one — and matches the movement poll and the autosave,
     * which hold theirs so a reload can retune them.
     */
    public void stop() {
        for (final Tick running : tasks.values()) {
            running.task().cancel();
        }
        tasks.clear();
        granted.clear();
        for (final UUID locked : skyLocks.keySet()) {
            final Player player = plugin.getServer().getPlayer(locked);
            if (player != null && plugin.getServer().isOwnedByCurrentRegion(player)) {
                player.resetPlayerTime();
                player.resetPlayerWeather();
            }
        }
        skyLocks.clear();
        for (final Watch watch : watches) {
            watch.holders().clear();
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        startTick(player);
        noteStaff(player, player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        stopTick(uuid);
        granted.remove(uuid);
        skyLocks.remove(uuid);
        for (final Watch watch : watches) {
            watch.holders().remove(uuid);
        }
    }

    /**
     * WorldGuard session handlers tick here rather than on their own task: this one already runs on
     * every player's entity scheduler once a second, which is the granularity WorldGuard's own
     * tick-driven handlers work at.
     */
    private void apply(final Player player, final long tick, final boolean sessions) {
        if (sessions) {
            SessionDispatch.tick(player);
        }
        // Healing a player on the death screen leaves them stuck, since vanilla will not respawn
        // anyone above 0 health.
        if (player.isDead()) {
            return;
        }
        final UUID id = player.getUniqueId();
        final RegionManager world = container.get(player.getWorld());
        if (world != null && !skyLocks.containsKey(id) && !granted.containsKey(id)
            && !world.anyRegionUses(Flags.HEAL_AMOUNT) && !world.anyRegionUses(Flags.FEED_AMOUNT)
            && !world.anyRegionUses(Flags.TIME_LOCK) && !world.anyRegionUses(Flags.WEATHER_LOCK)
            && !world.anyRegionUses(Flags.GIVE_EFFECTS) && !world.anyRegionUses(Flags.BLOCKED_EFFECTS)) {
            return;
        }
        final ApplicableRegionSet regions = query.getApplicableRegions(player);
        if (regions.worldUses(Flags.HEAL_AMOUNT) && due(tick, regions.queryValue(Flags.HEAL_DELAY))) {
            heal(player, regions);
        }
        if (regions.worldUses(Flags.FEED_AMOUNT) && due(tick, regions.queryValue(Flags.FEED_DELAY))) {
            feed(player, regions);
        }
        if (regions.worldUses(Flags.TIME_LOCK) || regions.worldUses(Flags.WEATHER_LOCK)
            || skyLocks.containsKey(player.getUniqueId())) {
            lockSky(player, regions);
        }
        if (regions.worldUses(Flags.GIVE_EFFECTS) || regions.worldUses(Flags.BLOCKED_EFFECTS)
            || granted.containsKey(player.getUniqueId())) {
            effects(player, regions, tick);
        }
    }

    /**
     * Whether this second is one the flag should act on. The service ticks once a second, so a delay
     * of {@code n} means acting every {@code n}th tick; unset or non-positive keeps the old
     * every-second behaviour rather than silently pausing the effect.
     */
    private static boolean due(final long tick, final @Nullable Integer delay) {
        return delay == null || delay <= 1 || tick % delay == 0L;
    }

    /**
     * Applies time-lock / weather-lock client-side, so one player standing in an eternal-night arena
     * does not change the sky for the whole server. Each half is touched only when its value changed,
     * and resets the moment its flag stops applying, so a half this service never set (weather another
     * plugin gave the player, say) is left alone.
     */
    private void lockSky(final Player player, final ApplicableRegionSet regions) {
        final UUID id = player.getUniqueId();
        final String time = regions.queryValue(Flags.TIME_LOCK);
        final String weather = regions.queryValue(Flags.WEATHER_LOCK);
        final SkyLock applied = skyLocks.get(id);
        final boolean timeChanged = !Objects.equals(applied == null ? null : applied.time(), time);
        final boolean weatherChanged = !Objects.equals(applied == null ? null : applied.weather(), weather);
        if (!timeChanged && !weatherChanged) {
            return;
        }

        if (timeChanged) {
            if (time == null) {
                player.resetPlayerTime();
            } else {
                final Long ticks = parseTime(time);
                if (ticks != null) {
                    player.setPlayerTime(ticks, false);
                }
            }
        }

        if (weatherChanged) {
            if (weather == null) {
                player.resetPlayerWeather();
            } else {
                final WeatherType type = switch (weather.trim().toLowerCase(Locale.ROOT)) {
                    case "clear", "sun", "sunny" -> WeatherType.CLEAR;
                    case "downfall", "rain", "storm", "thunder" -> WeatherType.DOWNFALL;
                    default -> null;
                };
                if (type != null) {
                    player.setPlayerWeather(type);
                }
            }
        }

        if (time == null && weather == null) {
            skyLocks.remove(id);
        } else {
            skyLocks.put(id, new SkyLock(time, weather));
        }
    }

    private static @Nullable Long parseTime(final String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "sunset", "dusk" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            case "sunrise", "dawn" -> 23000L;
            default -> {
                try {
                    yield Long.parseLong(raw.trim());
                } catch (final NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    /**
     * The feeding counterpart to {@link #heal}: same clamping, against the 0–20 food scale.
     */
    private void feed(final Player player, final ApplicableRegionSet regions) {
        final Integer amount = regions.queryValue(Flags.FEED_AMOUNT);
        if (amount == null || amount == 0) {
            return;
        }
        final int min = Math.max(0, orDefault(regions.queryValue(Flags.MIN_FOOD), 0));
        final int max = Math.min(20, orDefault(regions.queryValue(Flags.MAX_FOOD), 20));
        final int food = player.getFoodLevel();
        if (amount > 0 && food >= max) {
            return;
        }
        if (amount < 0 && food <= min) {
            return;
        }
        final int target = Math.clamp(food + amount, min, max);
        if (target != food) {
            player.setFoodLevel(target);
        }
    }

    private static int orDefault(final @Nullable Integer value, final int fallback) {
        return value != null ? value : fallback;
    }

    private void heal(final Player player, final ApplicableRegionSet regions) {
        final Double amount = regions.queryValue(Flags.HEAL_AMOUNT);
        if (amount == null || amount == 0.0) {
            return;
        }
        final AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttribute == null) {
            return;
        }
        final double maxHealth = maxHealthAttribute.getValue();
        final double min = orDefault(regions.queryValue(Flags.HEAL_MIN_HEALTH), 0.0);
        final double max = Math.min(maxHealth, orDefault(regions.queryValue(Flags.HEAL_MAX_HEALTH), maxHealth));
        final double health = player.getHealth();

        if (amount > 0 && health >= max) {
            return;
        }
        if (amount < 0 && health <= min) {
            return;
        }
        final double target = Math.max(0.0, Math.clamp(health + amount, min, max));
        if (target != health) {
            player.setHealth(target);
        }
    }

    /**
     * Reapplies give-effects and strips the ones the player has walked out of. The applied duration
     * outlives the reapply interval by enough that vanilla never starts its low-duration flash, so
     * expiry can no longer serve as the removal: what the region granted is tracked per player and
     * diffed against what it grants now. Only types this service applied are ever removed, so a drunk
     * potion survives unless the region happens to grant the same type.
     *
     * <p>An unchanged set is only re-sent every {@link #REAPPLY_SECONDS} seconds. The flag value is
     * the same object for as long as nobody edits it, so a change of region, amplifier or effect list
     * is caught by identity on the very next second and applied at once — the cadence only governs
     * the refresh of something already granted, which the client has for {@link #REAPPLY_TICKS}.
     */
    private void effects(final Player player, final ApplicableRegionSet regions, final long tick) {
        final UUID id = player.getUniqueId();
        final Set<PotionEffect> give = regions.queryValue(Flags.GIVE_EFFECTS);
        final Granted previous = granted.get(id);
        if (give == null || give.isEmpty()) {
            if (previous != null) {
                granted.remove(id);
                for (final PotionEffectType type : previous.types()) {
                    player.removePotionEffect(type);
                }
            }
        } else if (previous != null && previous.source() == give) {
            if (tick % REAPPLY_SECONDS == 0L) {
                for (final PotionEffect effect : previous.applied()) {
                    player.addPotionEffect(effect);
                }
            }
        } else {
            final Set<PotionEffectType> current = new HashSet<>(Math.max(4, give.size() * 2));
            final List<PotionEffect> applied = new ArrayList<>(give.size());
            for (final PotionEffect effect : give) {
                final PotionEffect refreshed = new PotionEffect(
                    effect.getType(), REAPPLY_TICKS, effect.getAmplifier(), true, false, false);
                player.addPotionEffect(refreshed);
                applied.add(refreshed);
                current.add(effect.getType());
            }
            if (previous != null) {
                for (final PotionEffectType type : previous.types()) {
                    if (!current.contains(type)) {
                        player.removePotionEffect(type);
                    }
                }
            }
            granted.put(id, new Granted(give, current, applied));
        }

        final Set<PotionEffect> blocked = regions.queryValue(Flags.BLOCKED_EFFECTS);
        if (blocked != null && !Bypass.has(player)) {
            for (final PotionEffect effect : blocked) {
                if (player.hasPotionEffect(effect.getType())) {
                    player.removePotionEffect(effect.getType());
                }
            }
        }
    }

    private static double orDefault(final @Nullable Double value, final double fallback) {
        return value != null ? value : fallback;
    }
}
