package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.event.RegionChangeEvent;
import com.tricrotism.uworldguard.event.RegionMembershipChangeEvent;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.RegionGroup;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Edits one world's regions the way uWorldGuard's own commands and menus do. Get one from
 * {@link RegionContainer#editor(World)}.
 *
 * <pre>{@code
 * RegionEditor editor = UWorldGuardApi.regionContainer().editor(world);
 * if (editor != null) {
 *     EditResult result = editor.setFlag(region, Flags.PVP, State.DENY, player);
 * }
 * }</pre>
 *
 * <p>Prefer this over mutating a {@link ProtectedRegion} or {@link RegionManager} directly. Every
 * call here:
 * <ul>
 *   <li>checks the edit is possible and returns a reason when it is not, instead of throwing;</li>
 *   <li>skips edits that change nothing, without firing anything;</li>
 *   <li>fires the matching {@link RegionChangeEvent}, so other plugins can veto an edit made by
 *       yours just as they can one made by an operator;</li>
 *   <li>marks the world dirty, so the edit is saved and the world's flag index is rebuilt. A direct
 *       {@code setFlag} that forgets that is never saved, and flag checks gated on
 *       {@code anyRegionUses} can skip the region.</li>
 * </ul>
 *
 * <p>{@code actor} is who the edit is for, and is told when another plugin refuses it. Pass
 * {@code null} for an edit your plugin makes on its own behalf.
 *
 * <p>Threading: safe from any thread. Events fire on the calling thread, flagged asynchronous when
 * that is not a tick thread. Listeners of other plugins run inside the call, so avoid holding locks
 * of your own across it.
 */
@NullMarked
public interface RegionEditor {

    /**
     * The world this editor edits.
     */
    World world();

    /**
     * That world's regions, for reading.
     */
    RegionManager manager();

    /**
     * Adds a new region. The region may already carry flags, owners and a priority; they are part of
     * what listeners see and approve.
     */
    EditResult create(ProtectedRegion region, @Nullable CommandSender actor);

    /**
     * Replaces the shape of the region with {@code replacement}'s id. Flags, owners, members,
     * priority and children carry over to the new shape.
     */
    EditResult redefine(ProtectedRegion replacement, @Nullable CommandSender actor);

    /**
     * Removes the region with that id. Its children lose their parent.
     */
    EditResult remove(String id, @Nullable CommandSender actor);

    /**
     * Sets a flag on the region, or clears it with {@code null}. A value keeps the group the flag
     * already applied to; clearing resets it.
     */
    <T> EditResult setFlag(ProtectedRegion region, Flag<T> flag, @Nullable T value, @Nullable CommandSender actor);

    /**
     * Sets a flag's value and the group it applies to as one edit, with one event to approve both.
     * Ignores {@code group} when clearing.
     */
    <T> EditResult setFlag(ProtectedRegion region, Flag<T> flag, @Nullable T value, RegionGroup group, @Nullable CommandSender actor);

    /**
     * Narrows which players a flag's value applies to. {@link RegionGroup#ALL} clears the
     * restriction. A flag the region does not set has nothing to narrow, and returns
     * {@link EditResult#INVALID}.
     */
    EditResult setFlagGroup(ProtectedRegion region, Flag<?> flag, RegionGroup group, @Nullable CommandSender actor);

    EditResult setPriority(ProtectedRegion region, int priority, @Nullable CommandSender actor);

    /**
     * Sets several priorities as one edit. A {@code RegionPriorityChangeEvent} fires for each region
     * whose priority moves, all before any is applied, and one cancellation cancels them all: an
     * ordering applied in part ranks regions in an order nobody asked for.
     */
    EditResult setPriorities(Map<ProtectedRegion, Integer> priorities, @Nullable CommandSender actor);

    /**
     * Sets the region's parent, or clears it with {@code null}. The parent must be in the same world.
     */
    EditResult setParent(ProtectedRegion region, @Nullable ProtectedRegion parent, @Nullable CommandSender actor);

    /**
     * Adds {@code player} as an owner or member.
     */
    EditResult addPlayer(ProtectedRegion region, RegionMembershipChangeEvent.Role role, UUID player, @Nullable CommandSender actor);

    /**
     * Removes {@code player} from the owners or members.
     */
    EditResult removePlayer(ProtectedRegion region, RegionMembershipChangeEvent.Role role, UUID player, @Nullable CommandSender actor);
}
