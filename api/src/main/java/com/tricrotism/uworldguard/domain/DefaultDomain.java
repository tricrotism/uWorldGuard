package com.tricrotism.uworldguard.domain;

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
