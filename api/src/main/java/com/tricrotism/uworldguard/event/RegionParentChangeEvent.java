package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a region's parent is set, changed or cleared. Cancelling keeps the old parent.
 *
 * <p>A parent passes its flags and its owners and members down, so this is the edit that changes
 * what a region protects without touching the region's own flags. A plugin guarding flag changes
 * should usually guard this too.
 *
 * <p>Fired only for a link that can be made: a change that would create a cycle is refused before
 * any listener is asked.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionParentChangeEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable ProtectedRegion oldParent;
    private final @Nullable ProtectedRegion newParent;

    public RegionParentChangeEvent(
        final World world, final ProtectedRegion region, final @Nullable ProtectedRegion oldParent,
        final @Nullable ProtectedRegion newParent, final @Nullable CommandSender actor
    ) {
        super(world, region, actor);
        this.oldParent = oldParent;
        this.newParent = newParent;
    }

    /**
     * The current parent, or {@code null} if the region has none.
     */
    public @Nullable ProtectedRegion getOldParent() {
        return oldParent;
    }

    /**
     * The parent about to be set, or {@code null} when the parent is being cleared.
     */
    public @Nullable ProtectedRegion getNewParent() {
        return newParent;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
