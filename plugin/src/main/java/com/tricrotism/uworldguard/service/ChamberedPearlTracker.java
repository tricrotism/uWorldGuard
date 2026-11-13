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

    /**
     * How long a tracked pearl may sit before it is dropped regardless. A thrown pearl resolves in a
     * couple of seconds; this only has to outlive the longest real flight.
     */
    private static final long EXPIRY_TICKS = 400L;

    private final Plugin plugin;
    private final Map<UUID, Set<EnderPearl>> byShooter = new ConcurrentHashMap<>();

    public ChamberedPearlTracker(final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts tracking a pearl, and arranges for it to stop being tracked whatever becomes of it.
     *
     * <p>{@link #untrack} runs off {@code ProjectileHitEvent}, which a pearl removed some other way —
     * its chunk unloading, another plugin despawning it — never fires, so its entry would hold a dead
     * entity until the shooter logged out. The task below covers both: the entity scheduler runs the
     * retirement callback if the pearl is gone before it fires, and the task itself if it is not.
     * Asking the pearl whether it is still alive would not do — it may sit in a region this thread
     * does not own, and its own scheduler is the one place that question is safe to ask.
     */
    public void track(final UUID shooter, final EnderPearl pearl) {
        byShooter.compute(shooter, (_, set) -> {
            final Set<EnderPearl> pearls = set != null ? set : ConcurrentHashMap.newKeySet();
            pearls.add(pearl);
            return pearls;
        });
        pearl.getScheduler().runDelayed(plugin, _ -> untrack(shooter, pearl), () -> untrack(shooter, pearl), EXPIRY_TICKS);
    }

    /**
     * One atomic step, because emptying the set and unmapping it are not: a pearl thrown in between
     * would be added to a set that is then dropped, and would never be enforced against.
     */
    public void untrack(final UUID shooter, final EnderPearl pearl) {
        byShooter.computeIfPresent(shooter, (_, set) -> {
            set.remove(pearl);
            return set.isEmpty() ? null : set;
        });
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
