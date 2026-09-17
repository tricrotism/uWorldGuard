package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;

/**
 * Fired when a world's regions are unloaded along with the world. From here on
 * {@code RegionContainer.get(world)} answers {@code null}. Drop anything cached for the world: the
 * manager and its regions are no longer the live ones, and holding them keeps the world reachable.
 *
 * <p>The regions are still being saved when this fires, so the manager is safe to read but must not
 * be edited.
 */
@NullMarked
public class RegionsUnloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final World world;
    private final RegionManager manager;

    public RegionsUnloadedEvent(final World world, final RegionManager manager) {
        super(!Bukkit.isPrimaryThread());
        this.world = world;
        this.manager = manager;
    }

    public World getWorld() {
        return world;
    }

    public RegionManager getManager() {
        return manager;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
