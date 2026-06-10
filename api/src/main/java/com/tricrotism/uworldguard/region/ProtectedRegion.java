package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.domain.Association;
import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.Flag;
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

    private volatile int priority;
    private volatile @Nullable ProtectedRegion parent;

    protected ProtectedRegion(final String id) {
        this.id = id;
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
     * Set the parent for flag inheritance.
     *
     * @throws IllegalArgumentException if it would create a circular relationship
     */
    public final void setParent(final @Nullable ProtectedRegion parent) {
        if (parent != null) {
            for (@Nullable ProtectedRegion p = parent; p != null; p = p.parent) {
                if (p == this) {
                    throw new IllegalArgumentException("Circular parent relationship");
                }
            }
        }
        this.parent = parent;
    }

    public final DefaultDomain getOwners() {
        return owners;
    }

    public final DefaultDomain getMembers() {
        return members;
    }

    /**
     * True if the player owns this region or any parent.
     */
    public final boolean isOwner(final UUID uuid) {
        for (@Nullable ProtectedRegion r = this; r != null; r = r.parent) {
            if (r.owners.containsPlayer(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the player owns or is a member of this region or any parent.
     */
    public final boolean isMember(final UUID uuid) {
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
}
