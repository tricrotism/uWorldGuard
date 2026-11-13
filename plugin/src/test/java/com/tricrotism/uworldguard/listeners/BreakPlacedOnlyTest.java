package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code break-placed-only} flag: an arena where players place and break freely, except that the
 * map itself cannot be broken, by hand or by explosion.
 */
class BreakPlacedOnlyTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;
    private RegionManager manager;
    private ProtectedCuboidRegion arena;
    private BuildProtectionListener build;
    private PlacedBlocks placed;
    private PlayerMock player;
    private RegionQuery query;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);

        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        manager = container.get(world);
        assertNotNull(manager);
        arena = new ProtectedCuboidRegion("arena", BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        arena.setFlag(Flags.BLOCK_BREAK, State.ALLOW);
        arena.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
        arena.setFlag(Flags.BREAK_PLACED_ONLY, State.ALLOW);
        manager.addRegion(arena);

        query = container.createQuery();
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
        final org.bukkit.block.BlockState before = block.getState();
        block.setType(type);
        final BlockPlaceEvent event = new BlockPlaceEvent(block, before, world.getBlockAt(x, y - 1, z),
            new ItemStack(type), player, true, EquipmentSlot.HAND);
        build.onPlace(event);
        assertFalse(event.isCancelled(), "placing is allowed in the arena");
        placed.onPlaced(event);
    }

    private boolean breakBlock(final int x, final int y, final int z) {
        final BlockBreakEvent event = new BlockBreakEvent(world.getBlockAt(x, y, z), player);
        build.onBreak(event);
        if (!event.isCancelled()) {
            placed.onBreak(event);
        }
        if (!event.isCancelled()) {
            placed.onBroken(event);
        }
        return !event.isCancelled();
    }

    @Test
    void aPlacedBlockCanBeBroken() {
        place(5, 64, 5, Material.COBBLESTONE);

        assertTrue(breakBlock(5, 64, 5));
    }

    @Test
    void theMapCannotBeBroken() {
        world.getBlockAt(6, 64, 6).setType(Material.STONE);

        assertFalse(breakBlock(6, 64, 6));
    }

    @Test
    void aBrokenPlacedBlockIsForgottenSoTheMapBlockRestoredThereStaysProtected() {
        place(5, 64, 5, Material.STONE);
        assertTrue(breakBlock(5, 64, 5));

        world.getBlockAt(5, 64, 5).setType(Material.STONE);
        assertFalse(breakBlock(5, 64, 5));
    }

    @Test
    void aDifferentBlockWhereOneWasPlacedCountsAsMap() {
        place(5, 64, 5, Material.DIRT);
        world.getBlockAt(5, 64, 5).setType(Material.STONE);

        assertFalse(breakBlock(5, 64, 5), "an arena reset put the map back over the placed block");
    }

    @Test
    void allowBlockBreakStillExemptsItsMaterials() {
        arena.setFlag(Flags.ALLOW_BLOCK_BREAK, Set.of(Material.GLASS));
        world.getBlockAt(7, 64, 7).setType(Material.GLASS);

        assertTrue(breakBlock(7, 64, 7));
    }

    @Test
    void outsideTheArenaNothingChanges() {
        world.getBlockAt(100, 64, 100).setType(Material.STONE);

        assertTrue(breakBlock(100, 64, 100));
    }

    @Test
    void explosionsOnlyDestroyPlacedBlocks() {
        place(5, 64, 5, Material.TNT);
        final Block map = world.getBlockAt(6, 64, 6);
        map.setType(Material.STONE);
        final Block built = world.getBlockAt(5, 64, 5);
        final List<Block> blocks = new ArrayList<>(List.of(built, map));
        final EntityExplodeEvent event = new EntityExplodeEvent(player, built.getLocation(), blocks, 1.0F,
            org.bukkit.ExplosionResult.DESTROY);

        placed.onEntityExplode(event);

        assertEquals(List.of(built), event.blockList());
    }

    @Test
    void anExplosionAcrossARegionEdgeIsJudgedPerBlock() {
        final ProtectedCuboidRegion keep = new ProtectedCuboidRegion("keep", BlockVector3.at(40, 0, 0),
            BlockVector3.at(41, 255, 32));
        keep.setFlag(Flags.TNT, State.DENY);
        manager.addRegion(keep);
        final List<Block> blocks = new ArrayList<>();
        for (int x = 38; x <= 43; x++) {
            blocks.add(world.getBlockAt(x, 64, 5));
        }

        query.removeDenied(world, blocks, Flags.TNT);

        assertEquals(List.of(38, 39, 42, 43), blocks.stream().map(Block::getX).toList(),
            "the run inside the denying region is spared and the answer flips back after it");
    }

    @Test
    void explodedPlacedBlocksAreForgottenSoTheRestoredMapStaysProtected() {
        place(5, 64, 5, Material.STONE);
        place(6, 64, 5, Material.STONE);
        final List<Block> blocks = new ArrayList<>(List.of(world.getBlockAt(5, 64, 5), world.getBlockAt(6, 64, 5)));
        final EntityExplodeEvent event = new EntityExplodeEvent(player, blocks.getFirst().getLocation(), blocks, 1.0F,
            org.bukkit.ExplosionResult.DESTROY);

        placed.onEntityExplode(event);
        assertEquals(2, event.blockList().size(), "both blocks were placed, so both may go");
        placed.onEntityExploded(event);

        assertFalse(breakBlock(5, 64, 5));
        assertFalse(breakBlock(6, 64, 5));
    }

    @Test
    void recordsHoldTheMaterialKeyNotItsOrdinal() {
        place(5, 64, 5, Material.COBBLESTONE);

        final long[] entries = world.getChunkAt(0, 0).getPersistentDataContainer()
            .get(new NamespacedKey(plugin, "placed-block-keys"), PersistentDataType.LONG_ARRAY);
        assertNotNull(entries);
        assertEquals(1, entries.length);
        assertEquals(Material.COBBLESTONE.getKey().toString().hashCode(), (int) entries[0]);
        assertTrue(breakBlock(5, 64, 5));
    }

    @Test
    void anUnloadedChunkIsReadBackFromItsDataNotFromMemory() {
        place(5, 64, 5, Material.COBBLESTONE);
        placed.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(0, 0)));
        world.getChunkAt(0, 0).getPersistentDataContainer().remove(new NamespacedKey(plugin, "placed-block-keys"));

        assertFalse(breakBlock(5, 64, 5), "with the chunk's record gone, the block reads as map");
    }

    @Test
    void ordinalRecordsUnderTheOldKeyAreIgnored() {
        final Block map = world.getBlockAt(5, 64, 5);
        map.setType(Material.STONE);
        final long position = ((long) (64 + 2048) << 8) | (5 << 4) | 5;
        world.getChunkAt(0, 0).getPersistentDataContainer().set(new NamespacedKey(plugin, "placed-blocks"),
            PersistentDataType.LONG_ARRAY, new long[]{(position << 32) | Material.STONE.ordinal()});

        assertFalse(breakBlock(5, 64, 5));
    }

    @Test
    void aGlobalFlagStillLetsPlayersBreakWhatTheyPlaced() {
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.BLOCK_BREAK, State.ALLOW);
        global.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
        global.setFlag(Flags.BREAK_PLACED_ONLY, State.ALLOW);
        manager.addRegion(global);

        place(100, 64, 100, Material.COBBLESTONE);
        assertTrue(breakBlock(100, 64, 100), "a block placed under the global flag was recorded");

        world.getBlockAt(101, 64, 101).setType(Material.STONE);
        assertFalse(breakBlock(101, 64, 101), "the global flag still keeps the map");
    }

    private boolean fill(final Block block) {
        final PlayerBucketFillEvent event = new PlayerBucketFillEvent(player, block, block, BlockFace.UP,
            Material.BUCKET, new ItemStack(Material.BUCKET), EquipmentSlot.HAND);
        build.onBucketFill(event);
        if (!event.isCancelled()) {
            placed.onBucketFill(event);
        }
        if (!event.isCancelled()) {
            placed.onBucketFilled(event);
        }
        return !event.isCancelled();
    }

    @Test
    void theMapsWaterCannotBeBucketed() {
        final Block pond = world.getBlockAt(6, 64, 6);
        pond.setType(Material.WATER);

        assertFalse(fill(pond));
    }

    @Test
    void waterAPlayerPouredCanBeTakenBack() {
        final Block spot = world.getBlockAt(7, 64, 7);
        final PlayerBucketEmptyEvent empty = new PlayerBucketEmptyEvent(player, spot, world.getBlockAt(7, 63, 7),
            BlockFace.UP, Material.WATER_BUCKET, new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND);
        build.onBucketEmpty(empty);
        assertFalse(empty.isCancelled());
        placed.onBucketEmptied(empty);
        spot.setType(Material.WATER);

        assertTrue(fill(spot));
    }

    @Test
    void aPistonCannotPushTheMap() {
        final Block map = world.getBlockAt(6, 64, 6);
        map.setType(Material.STONE);
        final BlockPistonExtendEvent event = new BlockPistonExtendEvent(world.getBlockAt(5, 64, 6),
            List.of(map), BlockFace.EAST);

        placed.onPistonExtend(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aPistonMayPushPlacedBlocks() {
        place(6, 64, 6, Material.COBBLESTONE);
        final BlockPistonExtendEvent event = new BlockPistonExtendEvent(world.getBlockAt(5, 64, 6),
            List.of(world.getBlockAt(6, 64, 6)), BlockFace.EAST);

        placed.onPistonExtend(event);

        assertFalse(event.isCancelled());
    }
}
