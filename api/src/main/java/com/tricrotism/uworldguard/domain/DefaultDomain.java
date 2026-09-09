package com.tricrotism.uworldguard.domain;

import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A set of players (by UUID) and permission groups. Used for both owners and members.
 * Thread-safe: queried from region threads while edited from command threads.
 */
@NullMarked
public final class DefaultDomain {

    /**
     * Prefix of the permission node a group's membership is read from, matching WorldGuard's own
     * convention and what LuckPerms grants every member of a group. Trusting {@code staff} means
     * trusting whoever holds {@code group.staff}.
     */
    private static final String GROUP_NODE_PREFIX = "group.";

    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final Set<String> groups = ConcurrentHashMap.newKeySet();

    public void addPlayer(final UUID uuid) {
        players.add(uuid);
    }

    public void removePlayer(final UUID uuid) {
        players.remove(uuid);
    }

    public void addGroup(final String group) {
        groups.add(group.toLowerCase(Locale.ROOT));
    }

    public void removeGroup(final String group) {
        groups.remove(group.toLowerCase(Locale.ROOT));
    }

    public boolean containsPlayer(final UUID uuid) {
        return players.contains(uuid);
    }

    public boolean containsGroup(final String group) {
        return groups.contains(group.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this domain trusts any group at all. This is the guard that keeps group support off the
     * membership hot path: a region trusting nobody by group answers from the empty set's own counter
     * and never reaches {@link #grants}, so it costs what it always did.
     */
    public boolean hasGroups() {
        return !groups.isEmpty();
    }

    /**
     * Whether {@code player} holds the permission node of a group this domain trusts.
     *
     * <p>Group membership lives in the permission plugin, not here, so it is read the only way Bukkit
     * offers: a node per trusted group. That makes it an online-player question — an offline player
     * has no permissible to ask, which is why {@link #containsPlayer} stays the check for anything
     * that must work without the player present.
     */
    public boolean grants(final Player player) {
        for (final String group : groups) {
            if (player.hasPermission(GROUP_NODE_PREFIX + group)) {
                return true;
            }
        }
        return false;
    }

    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public Set<String> getGroups() {
        return Collections.unmodifiableSet(groups);
    }

    public boolean isEmpty() {
        return players.isEmpty() && groups.isEmpty();
    }

    public int size() {
        return players.size() + groups.size();
    }
}
