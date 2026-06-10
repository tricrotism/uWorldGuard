package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Applies the heal-amount / heal-min-health / heal-max-health flags once per second.
 *
 * <p>Folia-correct: a global repeating task fans each player out to that player's own
 * entity scheduler, so health (Bukkit API) is only ever touched on the entity's region
 * thread. Region flag reads go through the thread-safe {@link RegionQuery}.
 */
@NullMarked
public final class HealService {

    private final Plugin plugin;
    private final RegionContainerImpl container;
    private final RegionQuery query;

    public HealService(final Plugin plugin, final RegionContainerImpl container, final RegionQuery query) {
        this.plugin = plugin;
        this.container = container;
        this.query = query;
    }

    public void start() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!container.anyRegionUses(Flags.HEAL_AMOUNT)) {
                return;
            }
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                player.getScheduler().run(plugin, t -> heal(player), null);
            }
        }, 20L, 20L);
    }

    @SuppressWarnings("deprecation")
    private void heal(final Player player) {
        final Location loc = player.getLocation();
        final ApplicableRegionSet regions = query.getApplicableRegions(loc);
        final Double amount = regions.queryValue(Flags.HEAL_AMOUNT);
        if (amount == null || amount == 0.0) {
            return;
        }
        final double maxHealth = player.getMaxHealth();
        final double min = orDefault(regions.queryValue(Flags.HEAL_MIN_HEALTH), 0.0);
        final double max = Math.min(maxHealth, orDefault(regions.queryValue(Flags.HEAL_MAX_HEALTH), maxHealth));
        final double health = player.getHealth();

        if (amount > 0 && health >= max) {
            return;
        }
        if (amount < 0 && health <= min) {
            return;
        }
        final double target = Math.max(0.0, Math.max(min, Math.min(max, health + amount)));
        if (target != health) {
            player.setHealth(target);
        }
    }

    private static double orDefault(final @Nullable Double value, final double fallback) {
        return value != null ? value : fallback;
    }
}
