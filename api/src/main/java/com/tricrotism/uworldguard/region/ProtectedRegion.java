package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.domain.Association;
import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A named, protected region. Shape-agnostic: subclasses define geometry via
 * {@link #contains(int, int, int)} and the bounding box used by the spatial index.
 * Everything else — id, priority, parent, membership, flags — lives here, so adding
 * a new shape never touches the manager, flags, storage, or commands.
 *
 * <p>Thread-safe: queried from region threads while edited from command threads.
 */
@NullMarked
public abstract class ProtectedRegion {

    private final String id;
    private final DefaultDomain owners = new DefaultDomain();
    private final DefaultDomain members = new DefaultDomain();
    private final Map<Flag<?>, Object> flags = new ConcurrentHashMap<>();
    private final Map<Flag<?>, Object> flagsView = Collections.unmodifiableMap(flags);
    private final Map<Flag<?>, RegionGroup> flagGroups = new ConcurrentHashMap<>();
    private final Map<Flag<?>, RegionGroup> flagGroupsView = Collections.unmodifiableMap(flagGroups);

    private volatile int priority;
    private volatile @Nullable ProtectedRegion parent;

    /**
     * Longest id accepted by {@link #isValidId}. Long enough for any descriptive name, short enough
     * that an id still reads as one in a chat line or on an item.
     */
    public static final int MAX_ID_LENGTH = 64;

    protected ProtectedRegion(final String id) {
        this.id = id;
    }

    /**
     * Whether {@code id} is safe to name a region. Letters, digits, underscore and hyphen only.
     *
     * <p>The exclusions are not cosmetic. Region ids become keys in the stored YAML document, and
     * Bukkit splits a configuration path on {@code .} — a region called {@code my.base} writes as a
     * nested {@code my: base:} pair and reads back as a region named {@code my} with no geometry,
     * which fails the whole world's load and disables saving for it. Whitespace and colons break the
     * document in the same family of ways.
     */
    public static boolean isValidId(final String id) {
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return false;
        }
        for (int i = 0, n = id.length(); i < n; i++) {
            final char c = id.charAt(i);
            if (c != '_' && c != '-' && !Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    public final String getId() {
        return id;
    }

    public abstract RegionType getType();

    /**
     * True if the block coordinate lies inside this region's volume.
     */
    public abstract boolean contains(int x, int y, int z);

    public final boolean contains(final BlockVector3 point) {
        return contains(point.x(), point.y(), point.z());
    }

    /**
     * Inclusive minimum corner of the axis-aligned bounding box (for indexing).
     */
    public abstract BlockVector3 getMinimumPoint();

    /**
     * Inclusive maximum corner of the axis-aligned bounding box (for indexing).
     */
    public abstract BlockVector3 getMaximumPoint();

    public final int getPriority() {
        return priority;
    }

    public final void setPriority(final int priority) {
        this.priority = priority;
    }

    public final @Nullable ProtectedRegion getParent() {
        return parent;
    }

    /**
     * Serialises every parent check-and-assign. The walk and the assignment have to be one step:
     * {@code setparent a b} and {@code setparent b a} on two region threads otherwise both finish
     * their walks before either assigns, and the pair ends up pointing at each other. Every reader of
     * the chain — {@link #isOwner}, {@link #isMember}, {@link #getFlag}, {@link #getFlagGroup} — is an
     * unbounded loop, so the next flag query on either region never returns and the thread that made
     * it is lost. A per-instance lock cannot see that interleave; parent edits are command-rate, so
     * one monitor for all of them is the cheapest thing that can. Readers stay lock-free: the
     * invariant protecting them is that no cycle ever becomes visible.
     */
    private static final Object PARENT_LOCK = new Object();

    /**
     * Set the parent for flag inheritance.
     *
     * @throws IllegalArgumentException if it would create a circular relationship
     */
    public final void setParent(final @Nullable ProtectedRegion parent) {
        synchronized (PARENT_LOCK) {
            if (parent != null) {
                for (@Nullable ProtectedRegion p = parent; p != null; p = p.parent) {
                    if (p == this) {
                        throw new IllegalArgumentException("Circular parent relationship");
                    }
                }
            }
            this.parent = parent;
        }
    }

    public final DefaultDomain getOwners() {
        return owners;
    }

    public final DefaultDomain getMembers() {
        return members;
    }

    /**
     * True if the player owns this region or any parent.
     *
     * <p>Always false when {@link Membership} is off, which is what makes that switch reach every
     * caller at once rather than only the ones somebody remembered to change. The lists themselves
     * are untouched, so turning it back on restores exactly what was there.
     */
    public final boolean isOwner(final UUID uuid) {
        if (!Membership.grantsTrust()) {
            return false;
        }
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.containsPlayer(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the player owns or is a member of this region or any parent. False throughout when
     * {@link Membership} is off, for the reason {@link #isOwner} gives.
     */
    public final boolean isMember(final UUID uuid) {
        if (!Membership.grantsTrust()) {
            return false;
        }
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.containsPlayer(uuid) || r.members.containsPlayer(uuid)) {
                return true;
            }
        }
        return false;
    }

    public final Association getAssociation(final UUID uuid) {
        if (isOwner(uuid)) {
            return Association.OWNER;
        }
        if (isMember(uuid)) {
            return Association.MEMBER;
        }
        return Association.NON_MEMBER;
    }

    /**
     * Flag value set on this region, inheriting from the parent chain; {@code null} if unset.
     */
    @SuppressWarnings("unchecked")
    public final <T> @Nullable T getFlag(final Flag<T> flag) {
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            final Object value = r.flags.get(flag);
            if (value != null) {
                return (T) value;
            }
        }
        return null;
    }

    public final <T> void setFlag(final Flag<T> flag, final @Nullable T value) {
        if (value == null) {
            flags.remove(flag);
        } else {
            flags.put(flag, value);
        }
    }

    /**
     * Live, unmodifiable view of flags set directly on this region (no inheritance) —
     * for storage and display. Mutate through {@link #setFlag}.
     */
    public final Map<Flag<?>, Object> getFlags() {
        return flagsView;
    }

    /**
     * Which players a flag's value applies to on this region, inheriting from the parent chain
     * alongside the value itself. {@link RegionGroup#ALL} when unqualified.
     */
    public final RegionGroup getFlagGroup(final Flag<?> flag) {
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (!r.flagGroups.isEmpty()) {
                final RegionGroup group = r.flagGroups.get(flag);
                if (group != null) {
                    return group;
                }
            }
            if (r.flags.containsKey(flag)) {
                return RegionGroup.ALL;
            }
        }
        return RegionGroup.ALL;
    }

    /**
     * Restrict a flag to a subset of players. {@code null} or {@link RegionGroup#ALL} clears it.
     */
    public final void setFlagGroup(final Flag<?> flag, final @Nullable RegionGroup group) {
        if (group == null || group == RegionGroup.ALL) {
            flagGroups.remove(flag);
        } else {
            flagGroups.put(flag, group);
        }
    }

    /**
     * Live, unmodifiable view of the group qualifiers set directly on this region — for storage.
     */
    public final Map<Flag<?>, RegionGroup> getFlagGroups() {
        return flagGroupsView;
    }

    /**
     * Copy everything that is not geometry from {@code other}: flags and their group qualifiers,
     * owners, members, priority and parent. Backs {@link RegionManager#redefineRegion}, where a
     * region is reshaped by building a new one of the wanted shape under the same id — bounds are
     * immutable, so the configuration has to move to the new instance rather than the shape moving
     * to the old one.
     */
    public final void copyStateFrom(final ProtectedRegion other) {
        flags.putAll(other.flags);
        flagGroups.putAll(other.flagGroups);
        copyDomain(other.owners, owners);
        copyDomain(other.members, members);
        priority = other.priority;
        setParent(other.parent);
    }

    private static void copyDomain(final DefaultDomain from, final DefaultDomain to) {
        for (final UUID uuid : from.getPlayers()) {
            to.addPlayer(uuid);
        }
        for (final String group : from.getGroups()) {
            to.addGroup(group);
        }
    }
}
