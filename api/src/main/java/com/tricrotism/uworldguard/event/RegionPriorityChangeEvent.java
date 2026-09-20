package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a region's priority changes. Cancelling keeps the old priority.
 *
 * <p>Reordering several regions at once ({@code /uwg priority shop>spawn}, or the priority dialog)
 * fires one of these per region whose number actually moves, all before any is applied. Cancelling
 * any one of them cancels the whole reorder, because applying part of an ordering leaves the regions
 * ranked in an order nobody asked for.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionPriorityChangeEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int oldPriority;
    private final int newPriority;

    public RegionPriorityChangeEvent(
        final World world, final ProtectedRegion region, final int oldPriority, final int newPriority,
        final @Nullable CommandSender actor
    ) {
        super(world, region, actor);
        this.oldPriority = oldPriority;
        this.newPriority = newPriority;
    }

    public int getOldPriority() {
        return oldPriority;
    }

    public int getNewPriority() {
        return newPriority;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
