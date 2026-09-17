package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;

/**
 * Fired once a world's regions have loaded and {@code RegionContainer.get(world)} starts answering
 * with its manager.
 *
 * <p>A world loaded into a running server has its regions read off the region thread, and until that
 * finishes the container answers {@code null} for it, which is indistinguishable from a world with
 * no regions at all. Build per-world caches here rather than on {@code WorldLoadEvent}, which fires
 * before any of this has happened.
 *
 * <p>For worlds present at startup this fires while uWorldGuard enables, before plugins that depend
 * on it have enabled. Those see the regions already loaded and can read them in their own enable.
 *
 * <p>Threading: asynchronous for a world loaded later, check {@link #isAsynchronous()}. Reading the
 * manager is safe from any thread.
 */
@NullMarked
public class RegionsLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final World world;
    private final RegionManager manager;

    public RegionsLoadedEvent(final World world, final RegionManager manager) {
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
