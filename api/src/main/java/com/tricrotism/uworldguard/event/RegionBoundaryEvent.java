package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.NullMarked;

/**
 * A player crossing one region's boundary. Listen to {@link RegionEnterEvent} or
 * {@link RegionExitEvent} for a direction, or this type for both.
 *
 * <p>Fired once per region actually crossed, not once per move: a player walking within a region
 * produces nothing, and stepping into three overlapping regions at once produces three events.
 * Regions the player was already inside are not re-announced.
 *
 * <p>These report a crossing that has already been allowed, so they are not cancellable. Denial is
 * the {@code entry} and {@code exit} flags' job, and it happens before any of this fires. A listener
 * that wants to turn a player back should teleport them, not expect a veto.
 *
 * <p>Threading: this fires on the region thread that owns the destination, which under Folia is not
 * a single shared thread and is not necessarily the one that owns anything else a listener touches.
 * Treat the player and the region as safe, and hop for anything else.
 */
@NullMarked
public abstract class RegionBoundaryEvent extends PlayerEvent {

    private final ProtectedRegion region;

    protected RegionBoundaryEvent(final Player player, final ProtectedRegion region) {
        super(player);
        this.region = region;
    }

    /**
     * The region being entered or left.
     */
    public ProtectedRegion getRegion() {
        return region;
    }
}
