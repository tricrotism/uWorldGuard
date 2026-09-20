package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Fired before a player is added to or removed from a region's owners or members. Cancelling leaves
 * the domain untouched.
 *
 * <p>Usually asynchronous: resolving a typed name to a UUID reads player data off disk, so the
 * command and the menu's add prompt do that off the region thread and this fires there. Removing
 * someone from the menu fires on the clicker's region thread instead. Check
 * {@link #isAsynchronous()} before touching the Bukkit API from a listener.
 *
 * <p>Fired for the edit as asked for, whether or not it changes anything — adding someone who is
 * already an owner still fires.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionMembershipChangeEvent extends RegionChangeEvent {

    /**
     * Which of a region's two trust lists an edit touches.
     */
    public enum Role {
        OWNER,
        MEMBER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Role role;
    private final UUID player;
    private final boolean adding;

    public RegionMembershipChangeEvent(
        final World world, final ProtectedRegion region, final Role role, final UUID player,
        final boolean adding, final @Nullable CommandSender actor
    ) {
        super(world, region, actor);
        this.role = role;
        this.player = player;
        this.adding = adding;
    }

    /**
     * Whether the edit touches the owners or the members.
     */
    public Role getRole() {
        return role;
    }

    /**
     * The player being added or removed. Resolved from a name, so they need not be online.
     */
    public UUID getPlayer() {
        return player;
    }

    /**
     * True for an addition, false for a removal.
     */
    public boolean isAdding() {
        return adding;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
