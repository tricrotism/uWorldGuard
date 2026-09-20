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
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The environment flags, which run on the busiest events the server fires. Each handler now answers
 * from the world's flag index before resolving anything, so these check that a flag nobody set still
 * lets the world alone and that one somebody set is still enforced.
 */
class NaturalProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private NaturalListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        container = new RegionContainerImpl(MockBukkit.createMockPlugin("uWorldGuard"), NO_STORE);
        container.loadAll();
        listener = new NaturalListener(container.createQuery());
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

    private boolean water(final int x, final int z) {
        final Block source = world.getBlockAt(x, 64, z);
        source.setType(Material.WATER);
        final BlockFromToEvent event = new BlockFromToEvent(source, world.getBlockAt(x + 1, 64, z));
        listener.onFromTo(event);
        return !event.isCancelled();
    }

    private boolean decay(final int x, final int z) {
        final Block leaves = world.getBlockAt(x, 64, z);
        leaves.setType(Material.OAK_LEAVES);
        final LeavesDecayEvent event = new LeavesDecayEvent(leaves);
        listener.onLeafDecay(event);
        return !event.isCancelled();
    }

    @Test
    void waterFlowsWhereNobodySetTheFlag() {
        claim("plot");

        assertTrue(water(16, 16));
    }

    @Test
    void waterFlowStopsAtARegionThatDeniesIt() {
        claim("plot").setFlag(Flags.WATER_FLOW, State.DENY);

        assertFalse(water(16, 16), "the flag is set on the region the water flows into");
        assertTrue(water(500, 500), "wilderness is unaffected");
    }

    @Test
    void leavesDecayWhereNobodySetTheFlag() {
        claim("plot");

        assertTrue(decay(16, 16));
    }

    @Test
    void leafDecayStopsInARegionThatDeniesIt() {
        claim("plot").setFlag(Flags.LEAF_DECAY, State.DENY);

        assertFalse(decay(16, 16));
        assertTrue(decay(500, 500));
    }

    private boolean harden(final int x, final int z, final Material into) {
        final Block lava = world.getBlockAt(x, 64, z);
        lava.setType(Material.LAVA);
        final BlockState formed = lava.getState();
        formed.setType(into);
        final BlockFormEvent event = new BlockFormEvent(lava, formed);
        listener.onForm(event);
        return !event.isCancelled();
    }

    @Test
    void lavaHardensWhereNobodySetTheFlag() {
        claim("plot");

        assertTrue(harden(16, 16, Material.OBSIDIAN));
    }

    @Test
    void lavaHardeningStopsInARegionThatDeniesIt() {
        claim("plot").setFlag(Flags.LAVA_HARDEN, State.DENY);

        assertFalse(harden(16, 16, Material.OBSIDIAN));
        assertFalse(harden(16, 17, Material.COBBLESTONE), "the cobblestone case too");
        assertTrue(harden(500, 500, Material.OBSIDIAN), "wilderness is unaffected");
    }

    @Test
    void lavaHardenLeavesOtherBlockFormingAlone() {
        claim("plot").setFlag(Flags.LAVA_HARDEN, State.DENY);

        assertTrue(harden(16, 16, Material.ICE), "ice forming answers to ice-form, not lava-harden");
    }

    @Test
    void aFlagSetAfterTheFirstEventTakesEffect() {
        final ProtectedCuboidRegion plot = claim("plot");
        assertTrue(water(16, 16));

        plot.setFlag(Flags.WATER_FLOW, State.DENY);

        assertFalse(water(16, 16), "the gate cannot cache a stale answer");
    }
}
