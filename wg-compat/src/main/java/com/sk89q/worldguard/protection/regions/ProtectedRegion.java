// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.regions;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.util.ChangeTracked;

import java.util.*;

/**
 * A protected region, backed one-to-one by a uWorldGuard
 * {@code com.tricrotism.uworldguard.region.ProtectedRegion}.
 *
 * <p>Every mutation is written straight through to the engine region and marks the owning world
 * dirty, because engine edits do not persist by themselves. A region built by a consumer
 * ({@code new ProtectedCuboidRegion(...)}) is detached: it has a backing engine region from the
 * start but no manager until {@code RegionManager.addRegion} attaches one.
 *
 * <p>Wrapping is canonical — wrapping the same engine region twice yields the same instance — so
 * identity comparison and {@link java.util.IdentityHashMap} keying behave as they do under real
 * WorldGuard.
 */
public abstract class ProtectedRegion implements ChangeTracked, Comparable<ProtectedRegion> {

    public static final String GLOBAL_REGION = "__global__";

    private static final String GROUP_SUFFIX = "-group";

    protected BlockVector3 min;
    protected BlockVector3 max;

    private final com.tricrotism.uworldguard.region.ProtectedRegion backing;
    private final DefaultDomain owners;
    private final DefaultDomain members;
    private final boolean transientRegion;

    private volatile com.tricrotism.uworldguard.region.RegionManager manager;
    private boolean dirty = true;

    protected ProtectedRegion(
        final com.tricrotism.uworldguard.region.ProtectedRegion backing, final boolean transientRegion
    ) {
        this.backing = backing;
        this.transientRegion = transientRegion;
        this.owners = DefaultDomain.uwgWrap(backing.getOwners(), this::markDirty);
        this.members = DefaultDomain.uwgWrap(backing.getMembers(), this::markDirty);
        setMinMaxFromBacking();
        com.tricrotism.uworldguard.wgcompat.RegionAdapters.link(backing, this);
    }

    /**
     * Internal: the engine region this wraps.
     */
    public final com.tricrotism.uworldguard.region.ProtectedRegion uwgBacking() {
        return backing;
    }

    /**
     * Internal: binds this region to the world manager that now owns it, so mutations persist.
     */
    public final void uwgAttach(final com.tricrotism.uworldguard.region.RegionManager manager) {
        this.manager = manager;
    }

    /**
     * Internal: the world manager this region is bound to, or {@code null} while it is detached.
     */
    public final com.tricrotism.uworldguard.region.RegionManager uwgManager() {
        return manager;
    }

    public String getId() {
        return backing.getId();
    }

    public abstract RegionType getType();

    public abstract List<BlockVector2> getPoints();

    public abstract boolean contains(BlockVector3 pt);

    public abstract boolean isPhysicalArea();

    public abstract int volume();

    public boolean contains(final int x, final int y, final int z) {
        return backing.contains(x, y, z);
    }

    public boolean contains(final BlockVector2 position) {
        return backing.contains(position.x(), min.y(), position.z());
    }

    public boolean containsAny(final List<BlockVector2> positions) {
        for (int i = 0, n = positions.size(); i < n; i++) {
            if (contains(positions.get(i))) {
                return true;
            }
        }
        return false;
    }

    public BlockVector3 getMinimumPoint() {
        return min;
    }

    public BlockVector3 getMaximumPoint() {
        return max;
    }

    public int getPriority() {
        return backing.getPriority();
    }

    public void setPriority(final int priority) {
        backing.setPriority(priority);
        markDirty();
    }

    public ProtectedRegion getParent() {
        final com.tricrotism.uworldguard.region.ProtectedRegion parent = backing.getParent();
        return parent == null ? null : com.tricrotism.uworldguard.wgcompat.RegionAdapters.region(parent, manager);
    }

    public void setParent(final ProtectedRegion parent) throws CircularInheritanceException {
        try {
            backing.setParent(parent == null ? null : parent.backing);
        } catch (final IllegalArgumentException circular) {
            throw new CircularInheritanceException();
        }
        markDirty();
    }

    public void clearParent() {
        backing.setParent(null);
        markDirty();
    }

    public DefaultDomain getOwners() {
        return owners;
    }

    public DefaultDomain getMembers() {
        return members;
    }

    public void setOwners(final DefaultDomain owners) {
        this.owners.clear();
        this.owners.addAll(owners);
    }

    public void setMembers(final DefaultDomain members) {
        this.members.clear();
        this.members.addAll(members);
    }

    public boolean hasMembersOrOwners() {
        return !backing.getOwners().isEmpty() || !backing.getMembers().isEmpty();
    }

    public boolean isOwner(final UUID uniqueId) {
        return backing.isOwner(uniqueId);
    }

    public boolean isMember(final UUID uniqueId) {
        return backing.isMember(uniqueId);
    }

    public boolean isOwner(final LocalPlayer player) {
        return backing.isOwner(player.getUniqueId());
    }

    public boolean isMember(final LocalPlayer player) {
        return backing.isMember(player.getUniqueId());
    }

    /**
     * A member but not an owner — WorldGuard's distinction for commands that treat the two
     * differently.
     */
    public boolean isMemberOnly(final LocalPlayer player) {
        final UUID uniqueId = player.getUniqueId();
        return backing.isMember(uniqueId) && !backing.isOwner(uniqueId);
    }

    /**
     * @deprecated uWorldGuard stores members by UUID; the name resolves from the server's player
     * cache only.
     */
    @Deprecated
    public boolean isOwner(final String playerName) {
        final UUID uniqueId = com.tricrotism.uworldguard.wgcompat.NameResolver.uuid(playerName);
        return uniqueId != null && backing.isOwner(uniqueId);
    }

    /**
     * @deprecated see {@link #isOwner(String)}.
     */
    @Deprecated
    public boolean isMember(final String playerName) {
        final UUID uniqueId = com.tricrotism.uworldguard.wgcompat.NameResolver.uuid(playerName);
        return uniqueId != null && backing.isMember(uniqueId);
    }

    @SuppressWarnings("unchecked")
    public <T extends Flag<V>, V> V getFlag(final T flag) {
        if (flag instanceof RegionGroupFlag groupFlag) {
            final com.tricrotism.uworldguard.flags.Flag<?> owner = groupFlag.uwgOwner();
            return owner == null ? null
                : (V) com.tricrotism.uworldguard.wgcompat.FlagBridge.toShimGroup(backing.getFlagGroup(owner));
        }
        final com.tricrotism.uworldguard.flags.Flag<Object> engine =
            com.tricrotism.uworldguard.wgcompat.FlagBridge.engineFlag(flag);
        if (engine == null) {
            return null;
        }
        return (V) com.tricrotism.uworldguard.wgcompat.FlagBridge.toShimValue(flag, backing.getFlag(engine));
    }

    public <T extends Flag<V>, V> void setFlag(final T flag, final V val) {
        if (flag instanceof RegionGroupFlag groupFlag) {
            final com.tricrotism.uworldguard.flags.Flag<?> owner = groupFlag.uwgOwner();
            if (owner != null) {
                backing.setFlagGroup(owner, com.tricrotism.uworldguard.wgcompat.FlagBridge.toEngineGroup(val));
            }
        } else {
            final com.tricrotism.uworldguard.flags.Flag<Object> engine =
                com.tricrotism.uworldguard.wgcompat.FlagBridge.engineFlag(flag);
            if (engine != null) {
                backing.setFlag(engine,
                    com.tricrotism.uworldguard.wgcompat.FlagBridge.toEngineValue(flag, val));
            }
        }
        markDirty();
    }

    /**
     * The flags set directly on this region — no inheritance from the parent chain, matching
     * WorldGuard. Values are in the shim representation.
     */
    public Map<Flag<?>, Object> getFlags() {
        final Map<com.tricrotism.uworldguard.flags.Flag<?>, Object> engineFlags = backing.getFlags();
        final Map<Flag<?>, Object> out = new HashMap<>(Math.max(4, engineFlags.size() * 2));
        for (final Map.Entry<com.tricrotism.uworldguard.flags.Flag<?>, Object> entry : engineFlags.entrySet()) {
            final Flag<?> shim =
                (Flag<?>) com.tricrotism.uworldguard.wgcompat.FlagBridge.wrapEngineFlag(entry.getKey());
            final Object value =
                com.tricrotism.uworldguard.wgcompat.FlagBridge.toShimValue(shim, entry.getValue());
            if (value != null) {
                out.put(shim, value);
            }
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setFlags(final Map<Flag<?>, Object> flags) {
        for (final com.tricrotism.uworldguard.flags.Flag<?> engine :
            new ArrayList<>(backing.getFlags().keySet())) {
            backing.setFlag((com.tricrotism.uworldguard.flags.Flag<Object>) engine, null);
        }
        if (flags != null) {
            for (final Map.Entry<Flag<?>, Object> entry : flags.entrySet()) {
                setFlag((Flag) entry.getKey(), entry.getValue());
            }
        }
        markDirty();
    }

    public void copyFrom(final ProtectedRegion other) {
        setPriority(other.getPriority());
        setOwners(other.getOwners());
        setMembers(other.getMembers());
        setFlags(other.getFlags());
        try {
            setParent(other.getParent());
        } catch (final CircularInheritanceException ignored) {
            // The source region's parent chain cannot be reused here; leave this region's parent alone.
        }
    }

    public List<ProtectedRegion> getIntersectingRegions(final Collection<ProtectedRegion> regions) {
        final List<ProtectedRegion> out = new ArrayList<>();
        for (final ProtectedRegion region : regions) {
            if (region != this && intersectsBoundingBox(region)) {
                out.add(region);
            }
        }
        return out;
    }

    /**
     * Bounding-box overlap. uWorldGuard indexes regions by bounding box, so this is also what
     * {@link #getIntersectingRegions} and {@link #intersectsEdges} answer with — an exact
     * edge-intersection test against arbitrary shapes is not available through the engine API.
     */
    protected boolean intersectsBoundingBox(final ProtectedRegion region) {
        final BlockVector3 otherMin = region.min;
        final BlockVector3 otherMax = region.max;
        return max.x() >= otherMin.x() && min.x() <= otherMax.x()
            && max.y() >= otherMin.y() && min.y() <= otherMax.y()
            && max.z() >= otherMin.z() && min.z() <= otherMax.z();
    }

    protected boolean intersectsEdges(final ProtectedRegion region) {
        return intersectsBoundingBox(region);
    }

    protected void setMinMaxPoints(final List<BlockVector3> points) {
        int loX = Integer.MAX_VALUE;
        int loY = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiY = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
        for (int i = 0, n = points.size(); i < n; i++) {
            final BlockVector3 point = points.get(i);
            loX = Math.min(loX, point.x());
            loY = Math.min(loY, point.y());
            loZ = Math.min(loZ, point.z());
            hiX = Math.max(hiX, point.x());
            hiY = Math.max(hiY, point.y());
            hiZ = Math.max(hiZ, point.z());
        }
        min = BlockVector3.at(loX, loY, loZ);
        max = BlockVector3.at(hiX, hiY, hiZ);
    }

    public boolean isTransient() {
        return transientRegion;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void setDirty(final boolean dirty) {
        this.dirty = dirty;
    }

    public static boolean isValidId(final String id) {
        return com.tricrotism.uworldguard.region.ProtectedRegion.isValidId(id);
    }

    @Override
    public int compareTo(final ProtectedRegion other) {
        final int byPriority = Integer.compare(other.getPriority(), getPriority());
        return byPriority != 0 ? byPriority : getId().compareTo(other.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public String toString() {
        return "ProtectedRegion{id=" + getId() + ", type=" + getType() + '}';
    }

    /**
     * Internal: records a mutation and marks the owning world for save.
     */
    protected final void markDirty() {
        dirty = true;
        com.tricrotism.uworldguard.wgcompat.CompatDiagnostics.REGION_MUTATIONS.increment();
        final com.tricrotism.uworldguard.region.RegionManager owner = manager;
        if (owner != null) {
            owner.markDirty();
        }
    }

    private void setMinMaxFromBacking() {
        final com.tricrotism.uworldguard.util.BlockVector3 lo = backing.getMinimumPoint();
        final com.tricrotism.uworldguard.util.BlockVector3 hi = backing.getMaximumPoint();
        min = BlockVector3.at(lo.x(), lo.y(), lo.z());
        max = BlockVector3.at(hi.x(), hi.y(), hi.z());
    }

    /**
     * Thrown when a parent assignment would create a cycle.
     */
    public static class CircularInheritanceException extends Exception {
    }
}
