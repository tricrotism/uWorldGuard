package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.storage.RegionStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Default {@link RegionContainer}. Holds one {@link RegionManager} per loaded world and
 * persists through a {@link RegionStore}. Loading/saving runs on the async scheduler so
 * file or database I/O never blocks a region thread; the managers themselves are
 * concurrent, so queries are safe while a load is still populating them.
 */
@NullMarked
public final class RegionContainerImpl implements RegionContainer {

    private final Plugin plugin;
    private final RegionStore store;
    private final Map<UUID, RegionManager> managers = new ConcurrentHashMap<>();
    private final Set<String> failedLoads = ConcurrentHashMap.newKeySet();

    public RegionContainerImpl(final Plugin plugin, final RegionStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void loadAll() {
        for (final World world : Bukkit.getWorlds()) {
            load(world);
        }
    }

    /**
     * Create (or replace) the manager for a world and populate it asynchronously.
     */
    public RegionManager load(final World world) {
        final RegionManager manager = new RegionManager();
        managers.put(world.getUID(), manager);
        final String name = world.getName();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                store.load(name, manager);
                failedLoads.remove(name);
            } catch (final Exception e) {
                failedLoads.add(name);
                plugin.getLogger().log(Level.SEVERE, "Failed to load regions for world " + name
                    + "; saving is disabled for this world to avoid overwriting stored regions.", e);
            }
            if (manager.getRegion(GlobalProtectedRegion.ID) == null) {
                manager.addRegion(new GlobalProtectedRegion());
            }
            manager.clearDirty();
        });
        return manager;
    }

    public void unload(final World world) {
        final RegionManager manager = managers.remove(world.getUID());
        if (manager != null) {
            saveAsync(world.getName(), manager);
        }
    }

    /**
     * Persist every dirty world off-thread.
     */
    public void saveAll() {
        managers.forEach((uid, manager) -> {
            final World world = Bukkit.getWorld(uid);
            if (world != null && manager.clearDirty()) {
                saveAsync(world.getName(), manager);
            }
        });
    }

    /**
     * Persist every world synchronously — for plugin shutdown, where the async scheduler is stopping.
     */
    public void saveAllBlocking() {
        managers.forEach((uid, manager) -> {
            final World world = Bukkit.getWorld(uid);
            if (world == null || failedLoads.contains(world.getName())) {
                return;
            }
            try {
                store.save(world.getName(), manager);
            } catch (final Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save regions for world " + world.getName(), e);
            }
        });
    }

    private void saveAsync(final String name, final RegionManager manager) {
        if (failedLoads.contains(name)) {
            plugin.getLogger().warning("Skipping region save for world " + name
                + ": its regions failed to load and saving would overwrite the stored data.");
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                store.save(name, manager);
            } catch (final Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save regions for world " + name, e);
            }
        });
    }

    @Override
    public @Nullable RegionManager get(final World world) {
        return managers.get(world.getUID());
    }

    /**
     * Whether any loaded world has a region setting {@code flag}. Cheap to poll: each manager
     * answers from a cached index. Lets periodic tasks skip work entirely when a flag is unused.
     */
    public boolean anyRegionUses(final com.tricrotism.uworldguard.flags.Flag<?> flag) {
        for (final RegionManager manager : managers.values()) {
            if (manager.anyRegionUses(flag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RegionQuery createQuery() {
        return new RegionQuery(this);
    }
}
