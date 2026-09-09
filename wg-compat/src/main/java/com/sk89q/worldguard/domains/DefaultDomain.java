// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.domains;

import com.sk89q.worldguard.util.ChangeTracked;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * A region's owner or member list: players by UUID plus permission groups.
 *
 * <p>Wraps a uWorldGuard {@code com.tricrotism.uworldguard.domain.DefaultDomain} in place — there is
 * no domain-swap setter on an engine region, so {@link #setPlayerDomain} and {@link #setGroupDomain}
 * replace the contents rather than the object. When the domain belongs to a region, every mutation
 * marks that region's world dirty.
 *
 * <p>The engine stores no names. Name-keyed reads resolve from the server's player cache and omit
 * anything unresolvable; name-keyed writes that cannot resolve are dropped with a one-time warning.
 */
public class DefaultDomain implements Domain, ChangeTracked {

    static final Runnable NO_CHANGE = () -> {};

    private final com.tricrotism.uworldguard.domain.DefaultDomain backing;
    private final Runnable onChange;
    private final PlayerDomain playerDomain;
    private final GroupDomain groupDomain;
    private boolean dirty = true;

    public DefaultDomain() {
        this(new com.tricrotism.uworldguard.domain.DefaultDomain(), NO_CHANGE);
    }

    public DefaultDomain(final DefaultDomain existing) {
        this();
        addAll(existing);
    }

    private DefaultDomain(final com.tricrotism.uworldguard.domain.DefaultDomain backing, final Runnable onChange) {
        this.backing = backing;
        this.onChange = onChange;
        this.playerDomain = new PlayerDomain(backing, onChange);
        this.groupDomain = new GroupDomain(backing, onChange);
    }

    /**
     * Internal: views an engine domain that belongs to a region, marking the region dirty on edit.
     */
    public static DefaultDomain uwgWrap(final com.tricrotism.uworldguard.domain.DefaultDomain backing,
                                        final Runnable onChange) {
        return new DefaultDomain(backing, onChange);
    }

    /**
     * Internal: the engine domain this view mutates.
     */
    public com.tricrotism.uworldguard.domain.DefaultDomain uwgBacking() {
        return backing;
    }

    public PlayerDomain getPlayerDomain() {
        return playerDomain;
    }

    public GroupDomain getGroupDomain() {
        return groupDomain;
    }

    /**
     * Replaces the players in this domain with those of {@code playerDomain}. The engine domain
     * object itself is never swapped.
     *
     * <p>The incoming ids are copied before this domain is cleared. WorldGuard swaps a reference
     * here, so handing back the domain's own {@code PlayerDomain} costs nothing there, and that is
     * the natural way to write back after editing one. Reading the live view after clearing it made
     * that same call empty the region's owners instead.
     */
    public void setPlayerDomain(final PlayerDomain playerDomain) {
        final Set<UUID> incoming = Set.copyOf(playerDomain.getUniqueIds());
        this.playerDomain.clear();
        for (final UUID uniqueId : incoming) {
            backing.addPlayer(uniqueId);
        }
        changed();
    }

    /**
     * Replaces the groups in this domain with those of {@code groupDomain}. Copied first, for the
     * reason {@link #setPlayerDomain} gives.
     */
    public void setGroupDomain(final GroupDomain groupDomain) {
        final Set<String> incoming = Set.copyOf(groupDomain.getGroups());
        this.groupDomain.clear();
        for (final String group : incoming) {
            backing.addGroup(group);
        }
        changed();
    }

    public Set<UUID> getUniqueIds() {
        return backing.getPlayers();
    }

    public Set<String> getGroups() {
        return backing.getGroups();
    }

    /**
     * Names for the players in this domain, resolved from the server's player cache. Players the
     * server has never seen are omitted.
     */
    public Set<String> getPlayers() {
        return resolveNames(backing.getPlayers());
    }

    public void addPlayer(final UUID uniqueId) {
        backing.addPlayer(uniqueId);
        changed();
    }

    /**
     * Adds a player by name, resolved from the server's player cache. An unresolvable name is dropped
     * and warned about once.
     */
    public void addPlayer(final String name) {
        final UUID uniqueId = com.tricrotism.uworldguard.wgcompat.NameResolver.uuid(name);
        if (uniqueId == null) {
            com.tricrotism.uworldguard.wgcompat.NameResolver.warnUnresolved("DefaultDomain.addPlayer(String)", name);
            com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.stub("DefaultDomain.addPlayer(String)");
            return;
        }
        addPlayer(uniqueId);
    }

    public void addGroup(final String name) {
        backing.addGroup(name);
        changed();
    }

    public void removePlayer(final UUID uuid) {
        backing.removePlayer(uuid);
        changed();
    }

    public void removePlayer(final String name) {
        final UUID uniqueId = com.tricrotism.uworldguard.wgcompat.NameResolver.uuid(name);
        if (uniqueId != null) {
            removePlayer(uniqueId);
        }
    }

    public void removeGroup(final String name) {
        backing.removeGroup(name);
        changed();
    }

    public void addAll(final DefaultDomain other) {
        for (final UUID uniqueId : other.getUniqueIds()) {
            backing.addPlayer(uniqueId);
        }
        for (final String group : other.getGroups()) {
            backing.addGroup(group);
        }
        changed();
    }

    public void removeAll(final DefaultDomain other) {
        for (final UUID uniqueId : other.getUniqueIds()) {
            backing.removePlayer(uniqueId);
        }
        for (final String group : other.getGroups()) {
            backing.removeGroup(group);
        }
        changed();
    }

    public void removeAll() {
        clear();
    }

    @Override
    public void clear() {
        for (final UUID uniqueId : backing.getPlayers()) {
            backing.removePlayer(uniqueId);
        }
        for (final String group : backing.getGroups()) {
            backing.removeGroup(group);
        }
        changed();
    }

    @Override
    public boolean contains(final UUID uniqueId) {
        return backing.containsPlayer(uniqueId);
    }

    /**
     * The one membership test that can answer for groups, because it is the one holding a player to
     * ask. {@link #contains(UUID)} and the name form cannot: a uuid has no permissions attached to
     * it, so both stay player-only, as they are in WorldGuard.
     */
    @Override
    public boolean contains(final com.sk89q.worldguard.LocalPlayer player) {
        return playerDomain.contains(player) || groupDomain.contains(player);
    }

    public void addPlayer(final com.sk89q.worldguard.LocalPlayer player) {
        addPlayer(player.getUniqueId());
    }

    public void removePlayer(final com.sk89q.worldguard.LocalPlayer player) {
        removePlayer(player.getUniqueId());
    }

    @Override
    @Deprecated
    public boolean contains(final String playerName) {
        final UUID uniqueId = com.tricrotism.uworldguard.wgcompat.NameResolver.uuid(playerName);
        return uniqueId != null && backing.containsPlayer(uniqueId);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void setDirty(final boolean dirty) {
        this.dirty = dirty;
    }

    public String toGroupsString() {
        return join(backing.getGroups(), "*");
    }

    /**
     * The players in this domain by name, comma separated. Players the server has never seen are
     * omitted, because the engine has no name to print for them.
     */
    public String toPlayersString() {
        return join(getPlayers(), "");
    }

    public String toUserFriendlyString() {
        final String players = toPlayersString();
        final String groups = toGroupsString();
        if (players.isEmpty()) {
            return groups;
        }
        return groups.isEmpty() ? players : players + "; " + groups;
    }

    @Override
    public String toString() {
        return "DefaultDomain{players=" + backing.getPlayers() + ", groups=" + backing.getGroups() + '}';
    }

    static Set<String> resolveNames(final Set<UUID> uniqueIds) {
        final Set<String> names = new LinkedHashSet<>(uniqueIds.size());
        for (final UUID uniqueId : uniqueIds) {
            final String name = com.tricrotism.uworldguard.wgcompat.NameResolver.name(uniqueId);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static String join(final Set<String> values, final String prefix) {
        final StringJoiner joiner = new StringJoiner(", ");
        for (final String value : values) {
            joiner.add(prefix + value);
        }
        return joiner.toString();
    }

    private void changed() {
        dirty = true;
        onChange.run();
    }
}
