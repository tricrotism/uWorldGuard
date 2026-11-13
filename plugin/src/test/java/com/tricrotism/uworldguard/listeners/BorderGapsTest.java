package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CowMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ways into a claim at x 0..15 that fire no block-place or block-break event: the off hand, bone
 * meal spreading from outside, and a mob changing a block across the border.
 */
class BorderGapsTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private BuildProtectionListener build;
    private NaturalListener natural;
    private EntityListener entities;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);

        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionQuery query = container.createQuery();
        build = new BuildProtectionListener(query, new MessageService(plugin), true);
        natural = new NaturalListener(query);
        entities = new EntityListener(container, query);

        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        manager.addRegion(new ProtectedCuboidRegion("claim", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 31)));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void theOffHandIsJudgedLikeTheMainHand() {
        final Block composter = world.getBlockAt(8, 64, 8);
        composter.setType(Material.COMPOSTER);
        final PlayerInteractEvent event = new PlayerInteractEvent(server.addPlayer(), Action.RIGHT_CLICK_BLOCK,
            new ItemStack(Material.WHEAT_SEEDS), composter, BlockFace.UP, EquipmentSlot.OFF_HAND);

        build.onInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    void boneMealFromOutsideDoesNotSpreadIntoTheClaim() {
        final Block clicked = world.getBlockAt(16, 64, 8);
        final List<BlockState> grown = new ArrayList<>(List.of(
            world.getBlockAt(15, 64, 8).getState(), world.getBlockAt(17, 64, 8).getState()));
        final BlockFertilizeEvent byPlayer = new BlockFertilizeEvent(clicked, server.addPlayer(), grown);

        natural.onFertilize(byPlayer);

        assertEquals(1, grown.size(), "the block inside the claim is dropped");
        assertEquals(17, grown.getFirst().getX());

        final List<BlockState> dispensed = new ArrayList<>(List.of(world.getBlockAt(15, 64, 8).getState()));
        final BlockFertilizeEvent byDispenser = new BlockFertilizeEvent(clicked, null, dispensed);

        natural.onFertilize(byDispenser);

        assertTrue(byDispenser.isCancelled(), "nothing it would grow is allowed");
    }

    private boolean mobChanges(final double mobX, final int blockX) {
        final CowMock mob = new CowMock(server, UUID.randomUUID());
        mob.teleport(new Location(world, mobX, 64, 8.5));
        final EntityChangeBlockEvent event = new EntityChangeBlockEvent(
            mob, world.getBlockAt(blockX, 64, 8), Material.AIR.createBlockData());
        entities.onMobGrief(event);
        return !event.isCancelled();
    }

    @Test
    void aMobChangesBlocksWhereItStandsButNotAcrossTheBorder() {
        assertFalse(mobChanges(16.5, 15), "outside the claim, changing a block inside it");
        assertTrue(mobChanges(14.5, 15), "inside the claim");
        assertTrue(mobChanges(16.5, 17), "outside any region");
    }
}
