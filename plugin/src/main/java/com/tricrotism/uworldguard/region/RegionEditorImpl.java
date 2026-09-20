package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.event.*;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.RegionGroup;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * The one implementation of every region edit, shared by the API, the commands and the menus, so
 * the checks, the events and the dirty marking cannot differ between them.
 */
@NullMarked
public final class RegionEditorImpl implements RegionEditor {

    private final World world;
    private final RegionManager manager;

    public RegionEditorImpl(final World world, final RegionManager manager) {
        this.world = world;
        this.manager = manager;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public RegionManager manager() {
        return manager;
    }

    @Override
    public EditResult create(final ProtectedRegion region, final @Nullable CommandSender actor) {
        if (!ProtectedRegion.isValidId(region.getId())) {
            return EditResult.INVALID;
        }
        if (manager.hasRegion(region.getId())) {
            return EditResult.ALREADY_EXISTS;
        }
        if (RegionEdits.vetoed(new RegionCreateEvent(world, region, actor))) {
            return EditResult.CANCELLED;
        }
        return manager.addRegionIfAbsent(region) == null ? EditResult.APPLIED : EditResult.ALREADY_EXISTS;
    }

    @Override
    public EditResult redefine(final ProtectedRegion replacement, final @Nullable CommandSender actor) {
        if (replacement instanceof GlobalProtectedRegion) {
            return EditResult.INVALID;
        }
        final ProtectedRegion existing = manager.getRegion(replacement.getId());
        if (existing == null) {
            return EditResult.NOT_FOUND;
        }
        if (existing instanceof GlobalProtectedRegion) {
            return EditResult.INVALID;
        }
        if (RegionEdits.vetoed(new RegionRedefineEvent(world, existing, replacement, actor))) {
            return EditResult.CANCELLED;
        }
        return manager.redefineRegion(replacement) == null ? EditResult.NOT_FOUND : EditResult.APPLIED;
    }

    @Override
    public EditResult remove(final String id, final @Nullable CommandSender actor) {
        final ProtectedRegion region = manager.getRegion(id);
        if (region == null) {
            return EditResult.NOT_FOUND;
        }
        if (region instanceof GlobalProtectedRegion) {
            return EditResult.INVALID;
        }
        if (RegionEdits.vetoed(new RegionRemoveEvent(world, region, actor))) {
            return EditResult.CANCELLED;
        }
        return manager.removeRegion(id) == null ? EditResult.NOT_FOUND : EditResult.APPLIED;
    }

    @Override
    public <T> EditResult setFlag(
        final ProtectedRegion region, final Flag<T> flag, final @Nullable T value,
        final @Nullable CommandSender actor
    ) {
        return setFlag(region, flag, value, directGroup(region, flag), actor);
    }

    @Override
    public <T> EditResult setFlag(
        final ProtectedRegion region, final Flag<T> flag, final @Nullable T value, final RegionGroup group,
        final @Nullable CommandSender actor
    ) {
        if (!owns(region)) {
            return EditResult.NOT_FOUND;
        }
        final Object current = region.getFlags().get(flag);
        final RegionGroup currentGroup = directGroup(region, flag);
        final RegionGroup nextGroup = value == null ? RegionGroup.ALL : group;
        if (Objects.equals(current, value) && currentGroup == nextGroup) {
            return EditResult.UNCHANGED;
        }
        if (RegionEdits.vetoed(new RegionFlagChangeEvent(
            world, region, flag, current, value, currentGroup, nextGroup, actor))) {
            return EditResult.CANCELLED;
        }
        region.setFlag(flag, value);
        region.setFlagGroup(flag, nextGroup);
        manager.markDirty();
        return EditResult.APPLIED;
    }

    @Override
    public EditResult setFlagGroup(
        final ProtectedRegion region, final Flag<?> flag, final RegionGroup group,
        final @Nullable CommandSender actor
    ) {
        if (!owns(region)) {
            return EditResult.NOT_FOUND;
        }
        final Object value = region.getFlags().get(flag);
        if (value == null) {
            return EditResult.INVALID;
        }
        final RegionGroup current = directGroup(region, flag);
        if (current == group) {
            return EditResult.UNCHANGED;
        }
        if (RegionEdits.vetoed(new RegionFlagChangeEvent(world, region, flag, value, value, current, group, actor))) {
            return EditResult.CANCELLED;
        }
        region.setFlagGroup(flag, group);
        manager.markDirty();
        return EditResult.APPLIED;
    }

    @Override
    public EditResult setPriority(final ProtectedRegion region, final int priority, final @Nullable CommandSender actor) {
        return setPriorities(Map.of(region, priority), actor);
    }

    @Override
    public EditResult setPriorities(final Map<ProtectedRegion, Integer> priorities, final @Nullable CommandSender actor) {
        final List<RegionPriorityChangeEvent> changes = new ArrayList<>(priorities.size());
        for (final Map.Entry<ProtectedRegion, Integer> entry : priorities.entrySet()) {
            final ProtectedRegion region = entry.getKey();
            if (!owns(region)) {
                return EditResult.NOT_FOUND;
            }
            final int next = entry.getValue();
            if (region.getPriority() != next) {
                changes.add(new RegionPriorityChangeEvent(world, region, region.getPriority(), next, actor));
            }
        }
        if (changes.isEmpty()) {
            return EditResult.UNCHANGED;
        }
        for (final RegionPriorityChangeEvent change : changes) {
            if (RegionEdits.vetoed(change)) {
                return EditResult.CANCELLED;
            }
        }
        for (final RegionPriorityChangeEvent change : changes) {
            change.getRegion().setPriority(change.getNewPriority());
        }
        manager.markDirty();
        return EditResult.APPLIED;
    }

    @Override
    public EditResult setParent(
        final ProtectedRegion region, final @Nullable ProtectedRegion parent, final @Nullable CommandSender actor
    ) {
        if (!owns(region) || (parent != null && !owns(parent))) {
            return EditResult.NOT_FOUND;
        }
        final ProtectedRegion current = region.getParent();
        if (current == parent) {
            return EditResult.UNCHANGED;
        }
        if (parent != null && createsCycle(region, parent)) {
            return EditResult.INVALID;
        }
        if (RegionEdits.vetoed(new RegionParentChangeEvent(world, region, current, parent, actor))) {
            return EditResult.CANCELLED;
        }
        try {
            region.setParent(parent);
        } catch (final IllegalArgumentException _) {
            return EditResult.INVALID;
        }
        manager.markDirty();
        return EditResult.APPLIED;
    }

    @Override
    public EditResult addPlayer(
        final ProtectedRegion region, final RegionMembershipChangeEvent.Role role, final UUID player,
        final @Nullable CommandSender actor
    ) {
        return membership(region, role, player, true, actor);
    }

    @Override
    public EditResult removePlayer(
        final ProtectedRegion region, final RegionMembershipChangeEvent.Role role, final UUID player,
        final @Nullable CommandSender actor
    ) {
        return membership(region, role, player, false, actor);
    }

    private EditResult membership(
        final ProtectedRegion region, final RegionMembershipChangeEvent.Role role, final UUID player,
        final boolean add, final @Nullable CommandSender actor
    ) {
        if (!owns(region)) {
            return EditResult.NOT_FOUND;
        }
        final DefaultDomain domain = role == RegionMembershipChangeEvent.Role.OWNER
            ? region.getOwners()
            : region.getMembers();
        if (domain.containsPlayer(player) == add) {
            return EditResult.UNCHANGED;
        }
        if (RegionEdits.vetoed(new RegionMembershipChangeEvent(world, region, role, player, add, actor))) {
            return EditResult.CANCELLED;
        }
        if (add) {
            domain.addPlayer(player);
        } else {
            domain.removePlayer(player);
        }
        manager.markDirty();
        return EditResult.APPLIED;
    }

    /**
     * Whether {@code region} is the live region under its id in this world, and not a stale copy or
     * one from another world that happens to share the id.
     */
    private boolean owns(final ProtectedRegion region) {
        return manager.getRegion(region.getId()) == region;
    }

    private static RegionGroup directGroup(final ProtectedRegion region, final Flag<?> flag) {
        final RegionGroup group = region.getFlagGroups().get(flag);
        return group == null ? RegionGroup.ALL : group;
    }

    /**
     * Checked before any listener is asked, so a listener never approves an edit that then fails.
     * {@link ProtectedRegion#setParent} still makes the authoritative check under its lock.
     */
    private static boolean createsCycle(final ProtectedRegion region, final ProtectedRegion parent) {
        for (@Nullable ProtectedRegion p = parent; p != null; p = p.getParent()) {
            if (p == region) {
                return true;
            }
        }
        return false;
    }
}
