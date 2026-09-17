package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a new region is added to a world. Cancelling leaves the world unchanged and the id
 * free.
 *
 * <p>{@link #getRegion()} is fully built — shape, id, and whatever the caller set on it — but is not
 * in the world yet, so a listener may still adjust it rather than veto.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionCreateEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public RegionCreateEvent(
        final World world, final ProtectedRegion region, final @Nullable CommandSender actor
    ) {
        super(world, region, actor);
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
