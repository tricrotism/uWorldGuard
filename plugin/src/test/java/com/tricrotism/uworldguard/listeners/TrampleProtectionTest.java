package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trampling, which uWorldGuard splits where WorldGuard does not. These cover that farmland and eggs
 * answer to their own flag and are not moved by each other's, on the player path and the mob path.
 */
class TrampleProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private CropTrampleListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        container = new RegionContainerImpl(MockBukkit.createMockPlugin("uWorldGuard"), NO_STORE);
        container.loadAll();
        listener = new CropTrampleListener(container.createQuery());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ProtectedCuboidRegion claim(final String id) {
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(region);
        return region;
    }

    private Block stepOn(final Material type, final int x, final int z) {
        final Block block = world.getBlockAt(x, 64, z);
        block.setType(type);
        return block;
    }

    private boolean playerSteps(final Material type, final int x, final int z) {
        final PlayerMock walker = server.addPlayer();
        final PlayerInteractEvent event = new PlayerInteractEvent(
            walker, Action.PHYSICAL, null, stepOn(type, x, z), BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerTrample(event);
        return event.useInteractedBlock() != Event.Result.DENY;
    }

    private boolean mobSteps(final Material type, final int x, final int z) {
        final EntityInteractEvent event =
            new EntityInteractEvent(server.addPlayer(), stepOn(type, x, z));
        listener.onEntityTrample(event);
        return !event.isCancelled();
    }

    @Test
    void eggsBreakWhereNobodySetTheFlag() {
        claim("plot");

        assertTrue(playerSteps(Material.TURTLE_EGG, 16, 16));
    }

    @Test
    void eggTrampleDenyStopsAPlayer() {
        claim("plot").setFlag(Flags.EGG_TRAMPLE, State.DENY);

        assertFalse(playerSteps(Material.TURTLE_EGG, 16, 16));
        assertFalse(playerSteps(Material.SNIFFER_EGG, 16, 17), "sniffer eggs count too");
        assertTrue(playerSteps(Material.TURTLE_EGG, 500, 500), "wilderness is unaffected");
    }

    @Test
    void eggTrampleDenyStopsAMob() {
        claim("plot").setFlag(Flags.EGG_TRAMPLE, State.DENY);

        assertFalse(mobSteps(Material.TURTLE_EGG, 16, 16));
    }

    @Test
    void denyingCropTrampleLeavesEggsAlone() {
        claim("plot").setFlag(Flags.CROP_TRAMPLE, State.DENY);

        assertFalse(playerSteps(Material.FARMLAND, 16, 16), "farmland is what was denied");
        assertTrue(playerSteps(Material.TURTLE_EGG, 16, 17), "eggs have their own flag");
    }

    @Test
    void denyingEggTrampleLeavesFarmlandAlone() {
        claim("plot").setFlag(Flags.EGG_TRAMPLE, State.DENY);

        assertTrue(playerSteps(Material.FARMLAND, 16, 16));
    }
}
