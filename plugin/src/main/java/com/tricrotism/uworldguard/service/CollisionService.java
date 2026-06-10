package com.tricrotism.uworldguard.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disables player collision inside flagged regions using a native scoreboard team whose collision
 * rule is {@code NEVER}. Team membership changes are serialised on the global region scheduler (the
 * scoreboard is shared server-wide), and a per-player state set means we only touch the team when a
 * player's collision state actually flips.
 *
 * <p>The team is resolved lazily on first use: {@code getScoreboardManager()} is null until the
 * server has finished starting, so it cannot be created at plugin enable.
 */
@NullMarked
public final class CollisionService {

    private static final String TEAM_NAME = "uwg_nocollision";

    private final Plugin plugin;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();
    private @Nullable Team team;

    public CollisionService(final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Enable or disable collision for a player, scheduling the team change only on a state flip.
     */
    public void set(final Player player, final boolean collisionDisabled) {
        final UUID uuid = player.getUniqueId();
        final String entry = player.getName();
        if (collisionDisabled) {
            if (disabled.add(uuid)) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> applyEntry(entry, true));
            }
        } else if (disabled.remove(uuid)) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> applyEntry(entry, false));
        }
    }

    /**
     * Runs on the global region thread, where scoreboard access is safe.
     */
    private void applyEntry(final String entry, final boolean add) {
        final Team resolved = team();
        if (resolved == null) {
            return;
        }
        if (add) {
            resolved.addEntry(entry);
        } else {
            resolved.removeEntry(entry);
        }
    }

    private @Nullable Team team() {
        Team resolved = team;
        if (resolved != null) {
            return resolved;
        }
        final ScoreboardManager manager = plugin.getServer().getScoreboardManager();
        if (manager == null) {
            return null;
        }
        final Scoreboard scoreboard = manager.getMainScoreboard();
        resolved = scoreboard.getTeam(TEAM_NAME);
        if (resolved == null) {
            resolved = scoreboard.registerNewTeam(TEAM_NAME);
        }
        resolved.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team = resolved;
        return resolved;
    }
}
