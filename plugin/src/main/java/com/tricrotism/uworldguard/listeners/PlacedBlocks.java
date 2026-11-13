package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@code break-placed-only} flag: in a region that sets it, players place freely but break only
 * blocks some player placed there, and explosions only destroy those. Built for arenas, where the map
 * must survive a match and everything built during it must not.
 *
 * <p>Placed blocks are recorded in their chunk's persistent data, so the record survives restarts,
 * belongs to the thread that owns the chunk, and is freed with the chunk. A loaded chunk's record is
 * also held in memory and written through, so a lookup does not copy it. Each entry holds the block's
 * position in the chunk and the material placed there. A block that no longer matches its record,
 * because an arena reset or another plugin put something else there, counts as not placed, and such
 * stale entries are swept out as the chunk's record grows.
 *
 * <p>Breaking covers players, bucket draining, explosions, a player's projectiles, withers, fluid
 * washing blocks away, and pistons. A piston may move or destroy placed blocks only, and a moved
 * placed block takes its record along. Falling blocks are not followed.
 *
 * <p>Every handler first asks whether any region in the world uses the flag, a bitset test, so worlds
 * without it pay nothing.
 */
@NullMarked
public final class PlacedBlocks implements Listener {

    private static final StateFlag FLAG = Flags.BREAK_PLACED_ONLY;

    /**
     * Each material's persisted identity, indexed by ordinal. Ordinals shift between Minecraft versions
     * and one jar runs on several, so records hold a hash of the material's key instead. Two materials
     * sharing a hash only matter if one replaces the other at the same position.
     */
    private static final int[] MATERIAL_IDS = materialIds();

    private static final long[] NONE = new long[0];
    private static final Function<UUID, Map<Long, long[]>> NEW_WORLD = _ -> new ConcurrentHashMap<>();

    private final RegionQuery query;
    private final MessageService messages;
    private final NamespacedKey key;
    /**
     * Each loaded chunk's record by world and chunk key, filled on first read and evicted on unload.
     * Concurrent because chunks in different regions are read from different threads.
     */
    private final Map<UUID, Map<Long, long[]>> cached = new ConcurrentHashMap<>();

    public PlacedBlocks(final Plugin plugin, final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
        // Renamed when entries switched from ordinals to key hashes, so old records are ignored.
        this.key = new NamespacedKey(plugin, "placed-block-keys");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(final BlockPlaceEvent event) {
        if (!query.usesFlag(event.getBlock().getWorld(), FLAG)) {
            return;
        }
        if (event instanceof BlockMultiPlaceEvent multi) {
            final Map<Long, Block> touched = new HashMap<>();
            for (final BlockState replaced : multi.getReplacedBlockStates()) {
                final Block block = replaced.getBlock();
                record(block, block.getType(), true, touched);
            }
            for (final Block inChunk : touched.values()) {
                persist(inChunk, read(inChunk));
            }
        } else {
            record(event.getBlock(), event.getBlock().getType(), true, null);
        }
    }

    /**
     * Runs after the build flags, so it only sees breaks they already allowed, and narrows those.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (EventGate.disabled(event) || !query.usesFlag(block.getWorld(), FLAG)) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!protects(set, player.getUniqueId(), block, block.getType()) || Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendRegionDeny(player, FLAG, set);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), FLAG)) {
            forget(block);
        }
    }

    /**
     * Draining a source is breaking it. A player may take back fluid they poured, never the map's.
     * Cauldrons stay where they are, so they are left to the bucket flags.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        final Block block = event.getBlock();
        if (EventGate.disabled(event) || !query.usesFlag(block.getWorld(), FLAG)) {
            return;
        }
        final Material fluid = BuildProtectionListener.fluidIn(block);
        if (fluid != Material.WATER && fluid != Material.LAVA && fluid != Material.POWDER_SNOW) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!protects(set, player.getUniqueId(), block, fluid) || Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendRegionDeny(player, FLAG, set);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFilled(final PlayerBucketFillEvent event) {
        final Block block = event.getBlock();
        final Material type = block.getType();
        if ((type == Material.WATER || type == Material.LAVA || type == Material.POWDER_SNOW)
            && query.usesFlag(block.getWorld(), FLAG)) {
            forget(block);
        }
    }

    /**
     * Bucket fluid never fires {@link BlockPlaceEvent}, so it is recorded here, before it lands. Water
     * poured into a waterlogged block or onto an existing source adds nothing the player could take
     * back, so neither is recorded.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmptied(final PlayerBucketEmptyEvent event) {
        final Block block = event.getBlock();
        if (!query.usesFlag(block.getWorld(), FLAG)) {
            return;
        }
        final Material fluid = BuildProtectionListener.fluidOf(event.getBucket());
        final BlockData data = block.getBlockData();
        if (fluid.isAir() || data instanceof Waterlogged
            || (data instanceof Levelled levelled && block.getType() == fluid && levelled.getLevel() == 0)) {
            return;
        }
        record(block, fluid, true, null);
    }

    /**
     * A player's projectile or a wither breaking a block. Anything else changing a block is left to
     * its own flags, and only changes that remove the block count as breaking it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        final Entity entity = event.getEntity();
        final @Nullable Player shooter;
        if (entity instanceof Projectile projectile) {
            if (!(projectile.getShooter() instanceof Player player)) {
                return;
            }
            shooter = player;
        } else if (entity instanceof Wither) {
            shooter = null;
        } else {
            return;
        }
        final Block block = event.getBlock();
        if (!event.getTo().isAir() || EventGate.disabled(event) || !query.usesFlag(block.getWorld(), FLAG)) {
            return;
        }
        final UUID subject = shooter == null ? null : shooter.getUniqueId();
        if (protects(query.getApplicableRegions(block), subject, block, block.getType())
            && (shooter == null || !Bypass.has(shooter))) {
            event.setCancelled(true);
        }
    }

    /**
     * Fluid washing away a torch, rail or flower breaks it. Flow into air or other fluid, nearly every
     * flow event, returns before any region lookup.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        if (!query.usesFlag(event.getBlock().getWorld(), FLAG)) {
            return;
        }
        final Block to = event.getToBlock();
        final Material type = to.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA || EventGate.disabled(event)) {
            return;
        }
        if (protects(query.getApplicableRegions(to), null, to, type)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (!EventGate.disabled(event) && movesMap(event.getBlock().getWorld(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (!EventGate.disabled(event) && movesMap(event.getBlock().getWorld(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtended(final BlockPistonExtendEvent event) {
        carry(event.getBlock(), event.getBlocks(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetracted(final BlockPistonRetractEvent event) {
        carry(event.getBlock(), event.getBlocks(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (!EventGate.disabled(event)) {
            keepMap(event.getEntity().getWorld(), event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (!EventGate.disabled(event)) {
            keepMap(event.getBlock().getWorld(), event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExploded(final EntityExplodeEvent event) {
        if (!event.blockList().isEmpty() && query.usesFlag(event.getEntity().getWorld(), FLAG)) {
            forgetAll(event.blockList(), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExploded(final BlockExplodeEvent event) {
        if (!event.blockList().isEmpty() && query.usesFlag(event.getBlock().getWorld(), FLAG)) {
            forgetAll(event.blockList(), null);
        }
    }

    /**
     * Whether the flag keeps this block: it applies here, the material is not exempt, and no player
     * placed it. {@code type} is what leaves the world, which is the fluid for a drained source.
     */
    private boolean protects(
        final ApplicableRegionSet set, final @Nullable UUID subject, final Block block, final Material type
    ) {
        return set.queryState(FLAG, subject) == State.ALLOW
            && !(subject == null ? set.flagSetContains(Flags.ALLOW_BLOCK_BREAK, type)
                : set.flagSetContains(Flags.ALLOW_BLOCK_BREAK, type, subject))
            && !isPlaced(block, type);
    }

    /**
     * Drops every block from an explosion that the flag protects and no player placed. The flag is
     * resolved for the whole blast through {@link RegionQuery#removeDenied}, which narrows regions to
     * the blast's bounding box once, and each chunk's record is read once.
     */
    private void keepMap(final World world, final List<Block> blocks) {
        if (blocks.isEmpty() || !query.usesFlag(world, FLAG)) {
            return;
        }
        final List<Block> flagged = new ArrayList<>(blocks);
        query.removeDenied(world, flagged, FLAG);
        if (flagged.isEmpty()) {
            return;
        }
        final boolean exemptions = query.usesFlag(world, Flags.ALLOW_BLOCK_BREAK);
        final Set<Block> map = Collections.newSetFromMap(new IdentityHashMap<>());
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        long[] entries = NONE;
        for (int i = 0, n = flagged.size(); i < n; i++) {
            final Block block = flagged.get(i);
            final Material type = block.getType();
            final int cx = block.getX() >> 4;
            final int cz = block.getZ() >> 4;
            if (cx != lastCx || cz != lastCz) {
                entries = read(block);
                lastCx = cx;
                lastCz = cz;
            }
            if (!isPlaced(entries, block, type)
                && !(exemptions && query.getApplicableRegions(block).flagSetContains(Flags.ALLOW_BLOCK_BREAK, type))) {
                map.add(block);
            }
        }
        if (!map.isEmpty()) {
            blocks.removeIf(map::contains);
        }
    }

    private boolean movesMap(final World world, final List<Block> moved) {
        if (moved.isEmpty() || !query.usesFlag(world, FLAG)) {
            return false;
        }
        for (int i = 0, n = moved.size(); i < n; i++) {
            final Block block = moved.get(i);
            if (protects(query.getApplicableRegions(block), null, block, block.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the record of every placed block a piston pushes or pulls to where it lands. The event
     * fires before the move, so each block still shows its own type. Blocks the piston destroys are
     * only forgotten. Each chunk's persistent data is written once, after every record has moved.
     */
    private void carry(final Block piston, final List<Block> moved, final boolean extending) {
        if (moved.isEmpty() || !query.usesFlag(piston.getWorld(), FLAG)
            || !(piston.getBlockData() instanceof Directional directional)) {
            return;
        }
        List<Block> carried = null;
        for (int i = 0, n = moved.size(); i < n; i++) {
            final Block block = moved.get(i);
            if (isPlaced(block, block.getType())) {
                if (carried == null) {
                    carried = new ArrayList<>();
                }
                carried.add(block);
            }
        }
        if (carried == null) {
            return;
        }
        final BlockFace facing = directional.getFacing();
        final BlockFace direction = extending ? facing : facing.getOppositeFace();
        final Map<Long, Block> touched = new HashMap<>();
        // Every source is forgotten first, so a block landing on the next one's old spot keeps its record.
        forgetAll(carried, touched);
        for (final Block block : carried) {
            if (block.getPistonMoveReaction() != PistonMoveReaction.BREAK) {
                record(block.getRelative(direction), block.getType(), false, touched);
            }
        }
        for (final Block inChunk : touched.values()) {
            persist(inChunk, read(inChunk));
        }
    }

    /**
     * Records {@code type} at {@code block} when a region there, or the global region, sets the flag
     * for any group, so the record exists for whoever the flag ends up restricting.
     *
     * <p>With {@code prune}, entries whose block has since changed are swept out first, but only when
     * the record has grown to a power of two, which keeps the sweep's cost constant per placement on
     * average. A piston carrying several blocks passes {@code false}: its destinations do not hold the
     * moved blocks yet and would read as stale.
     */
    private void record(
        final Block block, final Material type, final boolean prune, final @Nullable Map<Long, Block> deferred
    ) {
        if (query.getApplicableRegions(block).queryValue(FLAG) == null) {
            return;
        }
        final long[] known = read(block);
        long[] current = known;
        if (prune && current.length >= 64 && Integer.bitCount(current.length) == 1) {
            current = withoutStale(block, current);
        }
        final long position = position(block);
        final long entry = entry(position, type);
        final int at = find(current, position);
        final long[] next;
        if (at >= 0) {
            if (current[at] == entry && current == known) {
                return;
            }
            current[at] = entry;
            next = current;
        } else {
            final int insert = -at - 1;
            next = new long[current.length + 1];
            System.arraycopy(current, 0, next, 0, insert);
            next[insert] = entry;
            System.arraycopy(current, insert, next, insert + 1, current.length - insert);
        }
        write(block, next, deferred);
    }

    /**
     * One block, the per-break path: a search and, only when the block was recorded, one copy.
     */
    private void forget(final Block block) {
        final long[] entries = read(block);
        final int at = entries.length == 0 ? -1 : find(entries, position(block));
        if (at < 0) {
            return;
        }
        if (entries.length == 1) {
            write(block, NONE, null);
            return;
        }
        final long[] next = new long[entries.length - 1];
        System.arraycopy(entries, 0, next, 0, at);
        System.arraycopy(entries, at + 1, next, at, next.length - at);
        write(block, next, null);
    }

    /**
     * Groups by chunk so each chunk's record is read and written once, not once per block.
     */
    private void forgetAll(final List<Block> blocks, final @Nullable Map<Long, Block> deferred) {
        final Map<Long, List<Block>> byChunk = new HashMap<>();
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block block = blocks.get(i);
            byChunk.computeIfAbsent(Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4), _ -> new ArrayList<>())
                .add(block);
        }
        for (final List<Block> inChunk : byChunk.values()) {
            forgetIn(inChunk.getFirst(), inChunk, deferred);
        }
    }

    /**
     * Removes the entries of {@code blocks}, which all lie in the chunk of {@code inChunk}.
     */
    private void forgetIn(final Block inChunk, final List<Block> blocks, final @Nullable Map<Long, Block> deferred) {
        final long[] entries = read(inChunk);
        if (entries.length == 0) {
            return;
        }
        final boolean[] gone = new boolean[entries.length];
        int removed = 0;
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final int at = find(entries, position(blocks.get(i)));
            if (at >= 0 && !gone[at]) {
                gone[at] = true;
                removed++;
            }
        }
        if (removed == 0) {
            return;
        }
        if (removed == entries.length) {
            write(inChunk, NONE, deferred);
            return;
        }
        final long[] next = new long[entries.length - removed];
        int j = 0;
        for (int i = 0; i < entries.length; i++) {
            if (!gone[i]) {
                next[j++] = entries[i];
            }
        }
        write(inChunk, next, deferred);
    }

    /**
     * Whether a player placed {@code type} at this block's position.
     */
    private boolean isPlaced(final Block block, final Material type) {
        return isPlaced(read(block), block, type);
    }

    private static boolean isPlaced(final long[] entries, final Block block, final Material type) {
        final long position = position(block);
        final int at = find(entries, position);
        return at >= 0 && entries[at] == entry(position, type);
    }

    /**
     * The record of {@code block}'s chunk, read from the chunk's persistent data once and then kept in
     * memory until the chunk unloads. A persistent-data read copies the whole array, so this keeps a
     * break from copying its chunk's record before and after.
     */
    private long[] read(final Block block) {
        final Map<Long, long[]> world = cached.computeIfAbsent(block.getWorld().getUID(), NEW_WORLD);
        final Long chunk = Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4);
        final long[] known = world.get(chunk);
        if (known != null) {
            return known;
        }
        final long[] stored = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
        final long[] loaded = stored == null ? NONE : stored;
        world.put(chunk, loaded);
        return loaded;
    }

    /**
     * Writes through: the memory copy and the chunk's persistent data change together, so nothing is
     * owed to the disk when the chunk unloads or the server stops.
     *
     * <p>A handler that writes the same chunk several times passes {@code deferred} instead. Only the
     * memory copy changes, the chunk is noted there, and the handler persists each noted chunk once
     * before it returns.
     */
    private void write(final Block block, final long[] entries, final @Nullable Map<Long, Block> deferred) {
        final Long chunk = Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4);
        cached.computeIfAbsent(block.getWorld().getUID(), NEW_WORLD).put(chunk, entries);
        if (deferred == null) {
            persist(block, entries);
        } else {
            deferred.putIfAbsent(chunk, block);
        }
    }

    private void persist(final Block block, final long[] entries) {
        final PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        if (entries.length == 0) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.LONG_ARRAY, entries);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final Map<Long, long[]> world = cached.get(event.getWorld().getUID());
        if (world != null) {
            world.remove(event.getChunk().getChunkKey());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(final WorldUnloadEvent event) {
        cached.remove(event.getWorld().getUID());
    }

    /**
     * {@code entries} without those whose block no longer holds the recorded material. A block still
     * moving under a piston keeps its entry.
     */
    private static long[] withoutStale(final Block block, final long[] entries) {
        final World world = block.getWorld();
        final int baseX = block.getX() & ~15;
        final int baseZ = block.getZ() & ~15;
        final long[] kept = new long[entries.length];
        int n = 0;
        for (final long entry : entries) {
            final long position = entry >>> 32;
            final Material type = world.getType(
                baseX + (int) ((position >>> 4) & 15), (int) (position >>> 8) - 2048, baseZ + (int) (position & 15));
            if (type == Material.MOVING_PISTON || entry == entry(position, type)) {
                kept[n++] = entry;
            }
        }
        return n == entries.length ? entries : Arrays.copyOf(kept, n);
    }

    /**
     * Position within the chunk in the high word, material in the low word, so a sorted array is
     * sorted by position and one comparison checks both.
     */
    private static long entry(final long position, final Material type) {
        return (position << 32) | (MATERIAL_IDS[type.ordinal()] & 0xFFFFFFFFL);
    }

    private static long position(final Block block) {
        return ((long) (block.getY() + 2048) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private static int[] materialIds() {
        final Material[] materials = Material.values();
        final int[] ids = new int[materials.length];
        for (final Material material : materials) {
            if (!material.isLegacy()) {
                ids[material.ordinal()] = material.getKey().toString().hashCode();
            }
        }
        return ids;
    }

    /**
     * Binary search by position, ignoring the material half.
     *
     * @return the index, or {@code -(insertion point) - 1} as {@link Arrays#binarySearch} does
     */
    private static int find(final long[] entries, final long position) {
        int low = 0;
        int high = entries.length - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final long at = entries[mid] >>> 32;
            if (at < position) {
                low = mid + 1;
            } else if (at > position) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }
}
