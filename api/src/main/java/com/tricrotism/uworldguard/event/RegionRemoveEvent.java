package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a region is removed from a world. Cancelling keeps it.
 *
 * <p>This is the last point at which the region can be read, so a listener that mirrors regions
 * elsewhere should copy what it needs here rather than after the fact.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionRemoveEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public RegionRemoveEvent(
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
