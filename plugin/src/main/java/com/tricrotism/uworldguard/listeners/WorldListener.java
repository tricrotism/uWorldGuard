package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.region.RegionContainerImpl;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Loads/unloads a world's regions as worlds come and go after startup.
 */
@NullMarked
public final class WorldListener implements Listener {

    private final RegionContainerImpl container;

    public WorldListener(final RegionContainerImpl container) {
        this.container = container;
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        container.load(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(final WorldUnloadEvent event) {
        container.unload(event.getWorld());
    }
}
