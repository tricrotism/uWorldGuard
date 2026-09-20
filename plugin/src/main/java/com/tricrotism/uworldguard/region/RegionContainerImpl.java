package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.event.RegionsLoadedEvent;
import com.tricrotism.uworldguard.event.RegionsUnloadedEvent;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.VerboseLogging;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
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

    /**
     * A loaded world's regions together with the name they persist under. The name is held rather
     * than resolved: the autosave runs on the async scheduler, and {@code Bukkit.getWorld(uid)} walks
     * the server's world map, which is not concurrent — a world loading or unloading mid-sweep could
     * take the save down with a {@code ConcurrentModificationException}.
     */
    private record Loaded(String name, RegionManager manager) {}

    private final Plugin plugin;
    private final RegionStore store;
    private final Map<UUID, Loaded> loaded = new ConcurrentHashMap<>();
    /**
     * The managers in {@link #loaded}, republished whenever that map changes. {@link #anyRegionUses}
     * runs on hot event paths — hopper transfers, entity spawns, item use — and iterating the map
     * there allocates an iterator per call for a walk over two or three worlds. Reading a snapshot
     * array allocates nothing.
     */
    private volatile RegionManager[] managers = new RegionManager[0];
    private final Set<String> failedLoads = ConcurrentHashMap.newKeySet();
    /**
     * Worlds whose async populate is still in flight. The publish at the end of {@link #load} claims
     * its entry, so a world unloaded while its regions were being read is never published: without
     * it {@link #unload} had nothing to remove yet and the task afterwards put a manager for a world
     * that is gone into the map, where it stayed for the session.
     */
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    /**
     * Monitors guarding store access, one per world name by hash. Three writers can reach the same
     * world's document — the autosave, the shutdown save, and a world unload — and the YAML backend
     * stages every write through one fixed temp path per world, so two overlapping saves interleave
     * their writes and the first move promotes a torn document over the live file. That file then
     * fails to parse at next boot, which also disables saving for the world. Same shape as
     * {@code MessageService}'s messages.yml lock.
     *
     * <p>Reads take it too. A world unloaded and loaded again — {@code /mv unload} then
     * {@code /mv load}, or a per-match world being recycled — queues the unload's save and the load's
     * read on the async scheduler with no ordering between them, and a read that wins that race
     * returns the document as it stood before the unload. Every edit since the last autosave is then
     * silently back, and the next autosave writes the reverted state over the good one.
     *
     * <p>A fixed set of stripes rather than a monitor per name: two worlds sharing a stripe only ever
     * wait for each other on the async scheduler, whereas a map keyed by name has to answer what
     * removes an entry, and removing one is exactly what reopens the race above.
     */
    private static final int STORE_LOCKS = 16;
    private final Object[] storeLocks = newStoreLocks();

    private static Object[] newStoreLocks() {
        final Object[] locks = new Object[STORE_LOCKS];
        for (int i = 0; i < STORE_LOCKS; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object storeLock(final String world) {
        return storeLocks[Math.floorMod(world.hashCode(), STORE_LOCKS)];
    }

    public RegionContainerImpl(final Plugin plugin, final RegionStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /**
     * Loads every world already present, on the calling thread.
     *
     * <p>Blocking is the point. This runs from {@code onEnable}, before the server accepts anyone, so
     * the I/O costs a moment of startup and nothing else — whereas loading these asynchronously left
     * a window where {@link #get} answered "no manager" for a world that has regions, and
     * {@code RegionQuery} cannot tell that apart from wilderness. Worlds that appear later go through
     * {@link #load}, which has no such luxury.
     */
    public void loadAll() {
        for (final World world : Bukkit.getWorlds()) {
            final RegionManager manager = new RegionManager();
            final String name = world.getName();
            try {
                synchronized (storeLock(name)) {
                    store.load(name, manager);
                }
            } catch (final Exception e) {
                failedLoads.add(name);
                plugin.getLogger().log(Level.SEVERE, "Failed to load regions for world " + name
                    + "; saving is disabled for this world to avoid overwriting stored regions.", e);
            }
            if (manager.getRegion(GlobalProtectedRegion.ID) == null) {
                manager.addRegion(new GlobalProtectedRegion());
            }
            manager.clearDirty();
            loaded.put(world.getUID(), new Loaded(name, manager));
            republishManagers();
            warnAboutUnenforcedGroups(name, manager);
            Bukkit.getPluginManager().callEvent(new RegionsLoadedEvent(world, manager));
        }
    }

    /**
     * Create (or replace) the manager for a world and populate it asynchronously.
     *
     * <p>The manager is published only once it is populated. Publishing the empty one first and
     * filling it in afterwards left a window — short at startup, but a whole file or query's worth of
     * latency on a world loaded into a running server — in which every lookup answered "no regions
     * here", which listeners cannot tell apart from wilderness. For that window the world read as
     * completely unprotected. Until the load finishes {@link #get} returns {@code null}, which is the
     * "not loaded" answer callers already handle.
     */
    public RegionManager load(final World world) {
        final RegionManager manager = new RegionManager();
        final String name = world.getName();
        final UUID uid = world.getUID();
        loading.add(uid);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                synchronized (storeLock(name)) {
                    store.load(name, manager);
                }
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
            if (!loading.remove(uid)) {
                return;
            }
            loaded.put(uid, new Loaded(name, manager));
            republishManagers();
            FlagLifecycle.resolvePending(manager);
            warnAboutUnenforcedGroups(name, manager);
            Bukkit.getPluginManager().callEvent(new RegionsLoadedEvent(world, manager));
        });
        return manager;
    }

    /**
     * Logs the group qualifiers this world carries that their flag does not yet act on, and the
     * regions that opt out of build protection via passthrough. Both are cases where the stored
     * configuration and the enforced behaviour differ, so they are stated at load rather than left
     * for someone to deduce from a bug report.
     */
    private void warnAboutUnenforcedGroups(final String world, final RegionManager manager) {
        final List<FlagGroupSupport.Finding> findings = FlagGroupSupport.audit(world, manager);
        if (!findings.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (final FlagGroupSupport.Finding f : findings) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(f.region()).append('/').append(f.flag()).append('=').append(f.group().serialized());
            }
            plugin.getLogger().warning("World '" + world + "': " + findings.size()
                + " flag group qualifier(s) are stored but not yet enforced, so those flags apply to"
                + " everyone in the region: " + sb);
        }

        final List<String> passthrough = FlagGroupSupport.passthroughRegions(manager);
        if (VerboseLogging.enabled() && !passthrough.isEmpty()) {
            plugin.getLogger().info("World '" + world + "': " + passthrough.size()
                + " region(s) allow passthrough and so do not protect against building: "
                + String.join(", ", passthrough));
        }
    }

    public void unload(final World world) {
        loading.remove(world.getUID());
        final Loaded removed = loaded.remove(world.getUID());
        republishManagers();
        if (removed != null) {
            Bukkit.getPluginManager().callEvent(new RegionsUnloadedEvent(world, removed.manager()));
            saveAsync(removed.name(), removed.manager(), () -> {});
        }
    }

    /**
     * Persist every dirty world off-thread.
     */
    public void saveAll() {
        loaded.forEach((_, world) -> {
            final RegionManager manager = world.manager();
            if (manager.clearDirty()) {
                saveAsync(world.name(), manager, manager::markDirty);
            }
        });
    }

    /**
     * Persist every world synchronously — for plugin shutdown, where the async scheduler is stopping.
     */
    public void saveAllBlocking() {
        loaded.forEach((_, world) -> {
            final String name = world.name();
            if (failedLoads.contains(name)) {
                return;
            }
            final RegionManager manager = world.manager();
            try {
                synchronized (storeLock(name)) {
                    store.save(name, manager);
                }
            } catch (final Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save regions for world " + name, e);
            }
        });
    }

    /**
     * @param onFailure run when the write throws. The autosave consumes the dirty bit before
     *                  dispatching — it has to, or edits made while the write is in flight would be
     *                  cleared by it — so a failed write has to put the bit back, otherwise the world
     *                  looks clean and is never retried until something else edits it.
     */
    private void saveAsync(final String name, final RegionManager manager, final Runnable onFailure) {
        if (failedLoads.contains(name)) {
            plugin.getLogger().warning("Skipping region save for world " + name
                + ": its regions failed to load and saving would overwrite the stored data.");
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                synchronized (storeLock(name)) {
                    store.save(name, manager);
                }
            } catch (final Exception e) {
                onFailure.run();
                plugin.getLogger().log(Level.WARNING, "Failed to save regions for world " + name, e);
            }
        });
    }

    @Override
    public @Nullable RegionManager get(final World world) {
        final Loaded found = loaded.get(world.getUID());
        return found == null ? null : found.manager();
    }

    @Override
    public @Nullable RegionEditor editor(final World world) {
        final RegionManager manager = get(world);
        return manager == null ? null : new RegionEditorImpl(world, manager);
    }

    /**
     * Whether any loaded world has a region setting {@code flag}. Cheap to poll: each manager
     * answers from a cached index. Lets periodic tasks skip work entirely when a flag is unused.
     */
    public boolean anyRegionUses(final com.tricrotism.uworldguard.flags.Flag<?> flag) {
        final RegionManager[] snapshot = managers;
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i].anyRegionUses(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every loaded world's manager, as of the last load or unload. Immutable.
     */
    public List<RegionManager> managers() {
        return List.of(managers);
    }

    /**
     * Rebuilds the {@link #managers} snapshot. Called after every {@link #loaded} mutation; world
     * loads and unloads are rare enough that rebuilding beats keeping the two in step incrementally.
     */
    private void republishManagers() {
        final List<RegionManager> snapshot = new ArrayList<>(loaded.size());
        for (final Loaded world : loaded.values()) {
            snapshot.add(world.manager());
        }
        managers = snapshot.toArray(new RegionManager[0]);
    }

    @Override
    public RegionQuery createQuery() {
        return new RegionQuery(this);
    }
}
