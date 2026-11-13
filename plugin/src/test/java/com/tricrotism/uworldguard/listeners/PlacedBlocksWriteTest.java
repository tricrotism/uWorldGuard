package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.Directional;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.ChunkCoordinate;
import org.mockbukkit.mockbukkit.world.ChunkMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How often {@code break-placed-only} writes a chunk's persistent data, which copies the chunk's whole
 * record each time.
 */
class PlacedBlocksWriteTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    /**
     * Counts writes to chunk persistent data, which the mock world gives no other way to see.
     */
    private static final class CountingWorld extends WorldMock {

        private final Map<ChunkCoordinate, ChunkMock> chunks = new HashMap<>();
        private int writes;

        @Override
        public ChunkMock getChunkAt(final ChunkCoordinate coordinate) {
            return chunks.computeIfAbsent(coordinate, at -> new ChunkMock(this, at.getX(), at.getZ()) {
                private final PersistentDataContainer data = new PersistentDataContainerMock() {
                    @Override
                    public <T, Z> void set(
                        final NamespacedKey key, final PersistentDataType<T, Z> type, final Z value
                    ) {
                        writes++;
                        super.set(key, type, value);
                    }

                    @Override
                    public void remove(final NamespacedKey key) {
                        writes++;
                        super.remove(key);
                    }
                };

                @Override
                public PersistentDataContainer getPersistentDataContainer() {
                    return data;
                }
            });
        }
    }

    private CountingWorld world;
    private PluginMock plugin;
    private BuildProtectionListener build;
    private PlacedBlocks placed;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        final ServerMock server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = new CountingWorld();
        world.setName("world");
        server.addWorld(world);
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);

        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion arena = new ProtectedCuboidRegion("arena",
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        arena.setFlag(Flags.BLOCK_BREAK, State.ALLOW);
        arena.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
        arena.setFlag(Flags.BREAK_PLACED_ONLY, State.ALLOW);
        manager.addRegion(arena);

        final RegionQuery query = container.createQuery();
        final MessageService messages = new MessageService(plugin);
        build = new BuildProtectionListener(query, messages, true);
        placed = new PlacedBlocks(plugin, query, messages);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void place(final int x, final int y, final int z, final Material type) {
        final Block block = world.getBlockAt(x, y, z);
        final BlockState before = block.getState();
        block.setType(type);
        final BlockPlaceEvent event = new BlockPlaceEvent(block, before, world.getBlockAt(x, y - 1, z),
            new ItemStack(type), player, true, EquipmentSlot.HAND);
        build.onPlace(event);
        assertFalse(event.isCancelled());
        placed.onPlaced(event);
    }

    private void placeBed(final Block foot, final Block head) {
        final BlockState footBefore = foot.getState();
        final BlockState headBefore = head.getState();
        foot.setType(Material.RED_BED);
        head.setType(Material.RED_BED);
        placed.onPlaced(new BlockMultiPlaceEvent(List.of(footBefore, headBefore),
            world.getBlockAt(foot.getX(), foot.getY() - 1, foot.getZ()), new ItemStack(Material.RED_BED), player,
            true, EquipmentSlot.HAND));
    }

    private long[] stored(final int chunkX, final int chunkZ) {
        return world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer()
            .get(new NamespacedKey(plugin, "placed-block-keys"), PersistentDataType.LONG_ARRAY);
    }

    @Test
    void placingTheSameMaterialAgainWritesNothing() {
        place(5, 64, 5, Material.STONE_SLAB);
        world.writes = 0;

        place(5, 64, 5, Material.STONE_SLAB);

        assertEquals(0, world.writes, "doubling a slab leaves the record as it was");
        final long[] entries = stored(0, 0);
        assertNotNull(entries);
        assertEquals(1, entries.length);
    }

    @Test
    void aBedWritesEachChunkItSpansOnce() {
        world.writes = 0;
        placeBed(world.getBlockAt(5, 64, 5), world.getBlockAt(5, 64, 6));

        assertEquals(1, world.writes, "both halves land in one chunk, so its data is written once");
        final long[] entries = stored(0, 0);
        assertNotNull(entries);
        assertEquals(2, entries.length, "the one write holds both halves");

        world.writes = 0;
        placeBed(world.getBlockAt(15, 64, 8), world.getBlockAt(16, 64, 8));

        assertEquals(2, world.writes, "a bed across a chunk border writes each chunk");
        assertEquals(3, stored(0, 0).length);
        assertEquals(1, stored(1, 0).length);
    }

    private void pushEast(final int pistonX, final int y, final int z, final Material type) {
        final Block piston = world.getBlockAt(pistonX, y, z);
        piston.setType(Material.PISTON);
        final Directional facing = (Directional) piston.getBlockData();
        facing.setFacing(BlockFace.EAST);
        piston.setBlockData(facing);
        // BlockMock leaves getPistonMoveReaction unimplemented
        final Block moved = new BlockMock(type, new Location(world, pistonX + 1, y, z)) {
            @Override
            public PistonMoveReaction getPistonMoveReaction() {
                return PistonMoveReaction.MOVE;
            }
        };
        placed.onPistonExtended(new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.EAST));
    }

    private static long position(final int x, final int y, final int z) {
        return ((long) (y + 2048) << 8) | ((x & 15) << 4) | (z & 15);
    }

    @Test
    void aPushInsideAChunkWritesItOnce() {
        place(6, 64, 6, Material.COBBLESTONE);
        world.writes = 0;

        pushEast(5, 64, 6, Material.COBBLESTONE);

        assertEquals(1, world.writes, "forgetting the source and recording the destination share one write");
        final long[] entries = stored(0, 0);
        assertNotNull(entries);
        assertEquals(1, entries.length);
        assertEquals(position(7, 64, 6), entries[0] >>> 32, "only the destination is recorded");
    }

    @Test
    void aPushAcrossAChunkBorderWritesEachChunk() {
        place(15, 64, 6, Material.COBBLESTONE);
        world.writes = 0;

        pushEast(14, 64, 6, Material.COBBLESTONE);

        assertEquals(2, world.writes);
        assertNull(stored(0, 0), "the emptied chunk drops its record");
        assertEquals(1, stored(1, 0).length);
        assertEquals(position(16, 64, 6), stored(1, 0)[0] >>> 32);
    }

    @Test
    void aSweptRecordIsWrittenEvenWhenThePlacementChangesNothing() {
        for (int i = 0; i < 64; i++) {
            place(i & 7, 64, i >> 3, Material.STONE);
        }
        world.getBlockAt(0, 64, 0).setType(Material.DIRT);
        world.writes = 0;

        place(1, 64, 0, Material.STONE);

        assertEquals(1, world.writes, "the stale entry swept from memory must leave the chunk's data too");
        assertEquals(63, stored(0, 0).length);
    }
}
