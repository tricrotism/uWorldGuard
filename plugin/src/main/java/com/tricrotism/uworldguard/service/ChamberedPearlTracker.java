package com.tricrotism.uworldguard.service;

import org.bukkit.entity.EnderPearl;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each player's in-flight ender pearls so the {@code chambered-enderpearl} flag can remove
 * "chambered" pearls (thrown from outside a denied region) when the shooter enters one. Experimental.
 *
 * <p>Each pearl is removed on its own entity scheduler, since it may sit in a different region than
 * the player who threw it.
 */
@NullMarked
public final class ChamberedPearlTracker {

    private final Plugin plugin;
    private final Map<UUID, Set<EnderPearl>> byShooter = new ConcurrentHashMap<>();

    public ChamberedPearlTracker(final Plugin plugin) {
        this.plugin = plugin;
    }

    public void track(final UUID shooter, final EnderPearl pearl) {
        byShooter.computeIfAbsent(shooter, k -> ConcurrentHashMap.newKeySet()).add(pearl);
    }

    public void untrack(final UUID shooter, final EnderPearl pearl) {
        final Set<EnderPearl> set = byShooter.get(shooter);
        if (set != null) {
            set.remove(pearl);
        }
    }

    /**
     * Remove every in-flight pearl the shooter currently has chambered.
     */
    public void removeFor(final UUID shooter) {
        final Set<EnderPearl> set = byShooter.remove(shooter);
        if (set == null) {
            return;
        }
        for (final EnderPearl pearl : set) {
            pearl.getScheduler().run(plugin, task -> pearl.remove(), null);
        }
    }

    public void clear(final UUID shooter) {
        byShooter.remove(shooter);
    }
}
