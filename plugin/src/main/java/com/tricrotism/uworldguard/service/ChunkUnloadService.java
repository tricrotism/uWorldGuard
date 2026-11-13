package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionType;
import com.tricrotism.uworldguard.util.BlockVector3;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the chunk-unload flag: a chunk of a chunk-unload=DENY region that a player has stood in
 * keeps a plugin chunk ticket, so it stays loaded after they leave. Paper scopes these tickets to this
 * plugin and drops them on disable, so no shutdown cleanup is needed and other plugins' tickets are
 * never disturbed.
 *
 * <p>Deny never loads a chunk, as with WorldGuard 6, which cancelled the unload event. Two shapes
 * that went further were wrong. Ticketing every covered chunk at startup made Paper load each one
 * synchronously inside {@code addPluginChunkTicket}, freezing the server for seconds on a large spawn
 * and keeping all of it entity ticking with nobody online. Ticketing covered chunks as they loaded
 * flooded the region instead: a ticketed chunk is entity ticking, Paper loads the two rings around
 * it, those load events ticketed them in turn, and the first visit loaded the whole region. Players
 * are sampled once a second from {@link PlayerTickService}, so only chunks someone stood in are held,
 * and a player moving faster than a chunk a second can skip one.
 *
 * <p>A slow global task reconciles the covered set every few seconds, so flag changes, region
 * edits, and world loads are all picked up without per-edit hooks; the steady state issues no work.
 * The reconcile is skipped when no region uses the flag and nothing is covered, and so is each world
 * on its own terms: the signature walks every region in a world, so without the per-world test one
 * region setting the flag in one world cost a full walk of every other world every period, on the
 * global thread. A chunk no region covers any more loses its ticket on its own region thread, and a
 * per-region chunk-span cap stops one oversized region from pinning an unbounded number of chunks.
 */
@NullMarked
public final class ChunkUnloadService {

    private static final long PERIOD_TICKS = 100L;
    private static final int MAX_CHUNKS_PER_REGION = 4096;

    private final Plugin plugin;
    private final RegionContainerImpl container;
    private final Map<UUID, Set<Long>> wanted = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> held = new ConcurrentHashMap<>();
    private final Map<UUID, Long> signatures = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile @Nullable ScheduledTask task;

    public ChunkUnloadService(final Plugin plugin, final RegionContainerImpl container) {
        this.plugin = plugin;
        this.container = container;
    }

    public void start() {
        task = plugin.getServer().getGlobalRegionScheduler()
            .runAtFixedRate(plugin, _ -> reconcile(), PERIOD_TICKS, PERIOD_TICKS);
    }

    /**
     * Cancels the reconcile. The chunk tickets themselves need no cleanup — Paper scopes them to this
     * plugin and drops them on disable — but holding the handle means the service can be stopped
     * without one.
     */
    public void stop() {
        final ScheduledTask running = task;
        if (running != null) {
            running.cancel();
            task = null;
        }
    }

    /**
     * Forgets a world's ticket bookkeeping when it unloads. The server drops the real tickets with
     * the world, but {@code held} would go on claiming they exist and {@code signatures} would go
     * on matching — so if that world were loaded again the reconcile would decide there was nothing
     * to do and chunk-unload would quietly stop being enforced there for the rest of the session.
     */
    public void forget(final World world) {
        final UUID uid = world.getUID();
        wanted.remove(uid);
        held.remove(uid);
        signatures.remove(uid);
        warned.removeIf(key -> key.startsWith(uid + ":"));
    }

    /**
     * Tickets the chunk {@code player} stands in if a deny region covers it. Runs on the player's own
     * thread, which owns that chunk, and the chunk is loaded because they are in it.
     *
     * <p>The reconcile publishes a new covered set before it scans {@code held}, so re-reading it
     * after the add catches a chunk the scan already passed. Either side removes through
     * {@code holding.remove}, so the ticket is dropped once.
     */
    public void visit(final Player player) {
        if (wanted.isEmpty()) {
            return;
        }
        final World world = player.getWorld();
        final UUID uid = world.getUID();
        final Set<Long> chunks = wanted.get(uid);
        if (chunks == null) {
            return;
        }
        final int cx = Location.locToBlock(player.getX()) >> 4;
        final int cz = Location.locToBlock(player.getZ()) >> 4;
        final long key = key(cx, cz);
        if (!chunks.contains(key)) {
            return;
        }
        final Set<Long> holding = held.computeIfAbsent(uid, _ -> ConcurrentHashMap.newKeySet());
        if (!holding.add(key)) {
            return;
        }
        world.addPluginChunkTicket(cx, cz, plugin);
        final Set<Long> now = wanted.get(uid);
        if (now != chunks && (now == null || !now.contains(key)) && holding.remove(key)) {
            world.removePluginChunkTicket(cx, cz, plugin);
        }
    }

    private static long key(final int cx, final int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    private void reconcile() {
        if (!container.anyRegionUses(Flags.CHUNK_UNLOAD) && wanted.isEmpty()) {
            return;
        }
        for (final World world : plugin.getServer().getWorlds()) {
            final RegionManager manager = container.get(world);
            if (manager == null) {
                continue;
            }
            final UUID uid = world.getUID();
            if (!manager.anyRegionUses(Flags.CHUNK_UNLOAD) && !wanted.containsKey(uid)) {
                continue;
            }
            final long signature = signatureOf(manager);
            final Long previous = signatures.get(uid);
            if (previous != null && previous == signature) {
                continue;
            }

            final Set<Long> desired = desiredChunks(uid, manager);
            if (desired.isEmpty()) {
                wanted.remove(uid);
            } else {
                wanted.put(uid, desired);
            }
            final Set<Long> holding = held.get(uid);
            if (holding != null) {
                for (final Long key : holding) {
                    if (!desired.contains(key) && holding.remove(key)) {
                        release(world, key);
                    }
                }
            }
            signatures.put(uid, signature);
        }
    }

    /**
     * A cheap order-independent digest of every region that currently denies chunk-unload, covering
     * its identity and bounds — everything the ticket set is derived from. Order-independent because
     * region iteration order is not stable, and a reshuffle must not read as a change.
     */
    private static long signatureOf(final RegionManager manager) {
        long signature = 0L;
        for (final ProtectedRegion region : manager.getRegions()) {
            if (region.getType() == RegionType.GLOBAL || region.getFlag(Flags.CHUNK_UNLOAD) != State.DENY) {
                continue;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            long h = region.getId().hashCode();
            h = h * 31 + min.x();
            h = h * 31 + min.z();
            h = h * 31 + max.x();
            h = h * 31 + max.z();
            signature += h * 0x9E3779B97F4A7C15L;
        }
        return signature;
    }

    private Set<Long> desiredChunks(final UUID uid, final RegionManager manager) {
        final Set<Long> chunks = new HashSet<>();
        for (final ProtectedRegion region : manager.getRegions()) {
            if (region.getType() == RegionType.GLOBAL || region.getFlag(Flags.CHUNK_UNLOAD) != State.DENY) {
                continue;
            }
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            final int cxMin = min.x() >> 4;
            final int cxMax = max.x() >> 4;
            final int czMin = min.z() >> 4;
            final int czMax = max.z() >> 4;
            final long span = (long) (cxMax - cxMin + 1) * (czMax - czMin + 1);
            if (span > MAX_CHUNKS_PER_REGION) {
                if (warned.add(uid + ":" + region.getId())) {
                    plugin.getLogger().warning("Region '" + region.getId() + "' spans " + span
                        + " chunks (> " + MAX_CHUNKS_PER_REGION + "); chunk-unload not enforced for it.");
                }
                continue;
            }
            for (int cx = cxMin; cx <= cxMax; cx++) {
                for (int cz = czMin; cz <= czMax; cz++) {
                    chunks.add(key(cx, cz));
                }
            }
        }
        return chunks;
    }

    private void release(final World world, final long key) {
        final int cx = (int) (key >> 32);
        final int cz = (int) key;
        plugin.getServer().getRegionScheduler().execute(plugin, world, cx, cz,
            () -> world.removePluginChunkTicket(cx, cz, plugin));
    }
}
