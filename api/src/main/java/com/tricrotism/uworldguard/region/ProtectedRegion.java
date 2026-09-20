package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.domain.Association;
import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
    private volatile @Nullable Object compatShim;
    /**
     * The manager holding this region, once it has been added to one. Set so a flag edit can retire
     * that world's flag index itself: queries skip a flag the index says nobody uses, so an edit that
     * left the index stale would read as "not set" until something else marked the world dirty.
     */
    private volatile @Nullable RegionManager owner;
    /**
     * Stored flag entries no registered flag can read, by their key in storage, kept as written.
     * Allocated on first use: almost every region has none, and a map per region would be the whole
     * cost of the feature.
     */
    private volatile @Nullable Map<String, Object> unresolvedFlags;

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

    /**
     * Internal (compat layer): the WorldGuard-API shim built over this region, or {@code null} while
     * nothing has asked for one. Plugins should not call this.
     *
     * <p>Consumers of the shim compare regions by identity and use them as map keys, so wrapping the
     * same region twice has to yield the same instance. Holding that instance here rather than in a
     * side map makes the lookup a field read on the hottest path the compat layer has, and ties the
     * shim's lifetime to the region's: a deleted region takes its wrapper with it.
     */
    public final @Nullable Object uwgCompatShim() {
        return compatShim;
    }

    /**
     * Internal (compat layer): publish {@code shim} as this region's wrapper, returning whichever
     * instance won when two threads wrapped the same region at once. Plugins should not call this.
     */
    public final Object uwgLinkCompatShim(final Object shim) {
        synchronized (this) {
            final Object existing = compatShim;
            if (existing != null) {
                return existing;
            }
            compatShim = shim;
            return shim;
        }
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
     * True if the player owns this region or any parent, by uuid or by a trusted group.
     *
     * <p>Always false when {@link Membership} is off, which is what makes that switch reach every
     * caller at once rather than only the ones somebody remembered to change. The lists themselves
     * are untouched, so turning it back on restores exactly what was there.
     */
    public final boolean isOwner(final UUID uuid) {
        if (!Membership.grantsTrust()) {
            return false;
        }
        boolean groups = false;
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.containsPlayer(uuid)) {
                return true;
            }
            groups |= r.owners.hasGroups();
        }
        return groups && trustedByGroup(uuid, false);
    }

    /**
     * True if the player owns or is a member of this region or any parent, by uuid or by a trusted
     * group. False throughout when {@link Membership} is off, for the reason {@link #isOwner} gives.
     */
    public final boolean isMember(final UUID uuid) {
        if (!Membership.grantsTrust()) {
            return false;
        }
        boolean groups = false;
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.containsPlayer(uuid) || r.members.containsPlayer(uuid)) {
                return true;
            }
            groups |= r.owners.hasGroups() || r.members.hasGroups();
        }
        return groups && trustedByGroup(uuid, true);
    }

    /**
     * The group half of the membership tests, kept out of line because it is the rare half: it runs
     * only once the uuid walk has missed and some region in the chain actually trusts a group, so a
     * region that trusts nobody by group never resolves a player or touches a permission map. The
     * uuid walk stays first because it answers without leaving the region.
     *
     * <p>Resolving the player is what makes group trust an online-only answer, and the null is the
     * honest result rather than a failure: an offline uuid has no permissions to read.
     */
    private boolean trustedByGroup(final UUID uuid, final boolean includeMembers) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return false;
        }
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.grants(player) || (includeMembers && r.members.grants(player))) {
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
        edited();
    }

    /**
     * Internal: records which manager holds this region. Plugins should not call this.
     */
    final void uwgOwnedBy(final @Nullable RegionManager manager) {
        this.owner = manager;
    }

    /**
     * Tells the owning world its flag index and stored document are both out of date. A region not
     * yet added to a manager has nobody to tell, and needs nobody: adding it retires the index.
     */
    private void edited() {
        final RegionManager manager = owner;
        if (manager != null) {
            manager.markDirty();
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
        edited();
    }

    /**
     * Live, unmodifiable view of the group qualifiers set directly on this region — for storage.
     */
    public final Map<Flag<?>, RegionGroup> getFlagGroups() {
        return flagGroupsView;
    }

    /**
     * Internal (storage): stored flag entries this region carries for flags nobody has registered,
     * keyed as they are in storage ({@code name} for a value, {@code name-group} for its group).
     * They are written back unchanged on save, and applied when a matching flag registers. Plugins
     * should not call this.
     *
     * <p>Without them a flag whose plugin loaded late, failed to load, or was unloaded to be swapped
     * lost its value on every region at the next save.
     */
    public final Map<String, Object> getUnresolvedFlags() {
        final Map<String, Object> current = unresolvedFlags;
        return current == null ? Map.of() : Collections.unmodifiableMap(current);
    }

    /**
     * Internal (storage): keep {@code raw} under {@code key} until a flag can read it. Plugins
     * should not call this.
     */
    public final void putUnresolvedFlag(final String key, final Object raw) {
        Map<String, Object> current = unresolvedFlags;
        if (current == null) {
            synchronized (this) {
                current = unresolvedFlags;
                if (current == null) {
                    current = new ConcurrentHashMap<>(4);
                    unresolvedFlags = current;
                }
            }
        }
        current.put(key, raw);
    }

    /**
     * Internal (storage): take back the entry kept under {@code key}, or {@code null}. Plugins
     * should not call this.
     */
    public final @Nullable Object removeUnresolvedFlag(final String key) {
        final Map<String, Object> current = unresolvedFlags;
        return current == null ? null : current.remove(key);
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
        for (final Map.Entry<String, Object> entry : other.getUnresolvedFlags().entrySet()) {
            putUnresolvedFlag(entry.getKey(), entry.getValue());
        }
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
