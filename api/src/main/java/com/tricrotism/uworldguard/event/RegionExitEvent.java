package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;

/**
 * Fired after a player has left a region. See {@link RegionBoundaryEvent} for what "left" counts as,
 * and why this is not cancellable.
 */
@NullMarked
public class RegionExitEvent extends RegionBoundaryEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public RegionExitEvent(final Player player, final ProtectedRegion region) {
        super(player, region);
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
