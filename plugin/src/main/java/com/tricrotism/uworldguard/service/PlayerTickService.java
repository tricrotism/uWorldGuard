package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
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
 * {@link ApplicableRegionSet#worldUses} — except that a player still holding effects this service
 * granted keeps ticking the effect half until they are stripped.
 */
@NullMarked
public final class PlayerTickService implements Listener {

    private static final int REAPPLY_TICKS = 300;

    private final Plugin plugin;
    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final Map<UUID, Set<PotionEffectType>> granted = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    public PlayerTickService(final Plugin plugin, final RegionContainerImpl container, final RegionQuery query) {
        this.plugin = plugin;
        this.container = container;
        this.query = query;
    }

    public void start() {
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
     */
    private void startTick(final Player player) {
        final UUID uuid = player.getUniqueId();
        stopTick(uuid);
        final AtomicLong seconds = new AtomicLong();
        final ScheduledTask scheduled = player.getScheduler().runAtFixedRate(plugin, _ -> {
            final boolean sessions = SessionDispatch.ACTIVE;
            if (!sessions
                && !container.anyRegionUses(Flags.HEAL_AMOUNT)
                && !container.anyRegionUses(Flags.FEED_AMOUNT)
                && !container.anyRegionUses(Flags.TIME_LOCK)
                && !container.anyRegionUses(Flags.WEATHER_LOCK)
                && !container.anyRegionUses(Flags.GIVE_EFFECTS)
                && !container.anyRegionUses(Flags.BLOCKED_EFFECTS)) {
                return;
            }
            apply(player, seconds.incrementAndGet(), sessions);
        }, null, 20L, 20L);
        if (scheduled != null) {
            tasks.put(uuid, scheduled);
        }
    }

    private void stopTick(final UUID uuid) {
        final ScheduledTask existing = tasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }
    }

    /**
     * Cancels every tick. Paper drops a plugin's tasks on disable anyway, but holding the handles
     * means the service can be stopped without one — and matches the movement poll and the autosave,
     * which hold theirs so a reload can retune them.
     */
    public void stop() {
        for (final ScheduledTask running : tasks.values()) {
            running.cancel();
        }
        tasks.clear();
        granted.clear();
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        startTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        stopTick(uuid);
        granted.remove(uuid);
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
        final ApplicableRegionSet regions = query.getApplicableRegions(player);
        if (regions.worldUses(Flags.HEAL_AMOUNT) && due(tick, regions.queryValue(Flags.HEAL_DELAY))) {
            heal(player, regions);
        }
        if (regions.worldUses(Flags.FEED_AMOUNT) && due(tick, regions.queryValue(Flags.FEED_DELAY))) {
            feed(player, regions);
        }
        if (regions.worldUses(Flags.TIME_LOCK) || regions.worldUses(Flags.WEATHER_LOCK)) {
            lockSky(player, regions);
        }
        if (regions.worldUses(Flags.GIVE_EFFECTS) || regions.worldUses(Flags.BLOCKED_EFFECTS)
            || granted.containsKey(player.getUniqueId())) {
            effects(player, regions);
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
     * does not change the sky for the whole server. Both reset the moment the flag stops applying,
     * which is why the reset runs even when the value is absent.
     */
    private void lockSky(final Player player, final ApplicableRegionSet regions) {
        final String time = regions.queryValue(Flags.TIME_LOCK);
        if (time == null) {
            player.resetPlayerTime();
        } else {
            final Long ticks = parseTime(time);
            if (ticks != null) {
                player.setPlayerTime(ticks, false);
            }
        }

        final String weather = regions.queryValue(Flags.WEATHER_LOCK);
        if (weather == null) {
            player.resetPlayerWeather();
            return;
        }
        final WeatherType type = switch (weather.trim().toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny" -> WeatherType.CLEAR;
            case "downfall", "rain", "storm", "thunder" -> WeatherType.DOWNFALL;
            default -> null;
        };
        if (type != null) {
            player.setPlayerWeather(type);
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
     * expiry can no longer serve as the removal: what the region granted last second is tracked per
     * player and diffed against what it grants now. Only types this service applied are ever removed,
     * so a drunk potion survives unless the region happens to grant the same type.
     */
    private void effects(final Player player, final ApplicableRegionSet regions) {
        final UUID id = player.getUniqueId();
        final Set<PotionEffect> give = regions.queryValue(Flags.GIVE_EFFECTS);
        final Set<PotionEffectType> previous = granted.get(id);
        if (give == null || give.isEmpty()) {
            if (previous != null) {
                granted.remove(id);
                for (final PotionEffectType type : previous) {
                    player.removePotionEffect(type);
                }
            }
        } else {
            final Set<PotionEffectType> current = new HashSet<>(give.size());
            for (final PotionEffect effect : give) {
                player.addPotionEffect(new PotionEffect(
                    effect.getType(), REAPPLY_TICKS, effect.getAmplifier(), true, false, false));
                current.add(effect.getType());
            }
            if (previous != null) {
                for (final PotionEffectType type : previous) {
                    if (!current.contains(type)) {
                        player.removePotionEffect(type);
                    }
                }
            }
            granted.put(id, current);
        }

        final Set<PotionEffect> blocked = regions.queryValue(Flags.BLOCKED_EFFECTS);
        if (blocked != null) {
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
