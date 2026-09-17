package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a region is reshaped. {@link #getRegion()} is the region as it stands, and
 * {@link #getReplacement()} the shape about to take its place; the replacement inherits the
 * existing region's flags, members, priority and children.
 *
 * <p>Cancelling leaves the existing shape in force. This is the event to watch for a claim that
 * must not grow past a limit, since the two shapes are both in hand.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionRedefineEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ProtectedRegion replacement;

    public RegionRedefineEvent(
        final World world, final ProtectedRegion existing, final ProtectedRegion replacement,
        final @Nullable CommandSender actor
    ) {
        super(world, existing, actor);
        this.replacement = replacement;
    }

    /**
     * The new shape, not yet in the world.
     */
    public ProtectedRegion getReplacement() {
        return replacement;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
