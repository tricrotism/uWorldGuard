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
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A piston in one region pushing into another. The point of interest is
 * {@code nonplayer-protection-domains}, which lets two regions agree to stop protecting each other
 * from machinery without either of them opening up to everything else.
 */
class PistonProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private PistonListener listener;
    private ProtectedCuboidRegion redstoneRoom;
    private ProtectedCuboidRegion farm;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        container = new RegionContainerImpl(MockBukkit.createMockPlugin("uWorldGuard"), NO_STORE);
        container.loadAll();
        listener = new PistonListener(container.createQuery());

        redstoneRoom = claim("redstone-room", 0, 15);
        farm = claim("farm", 16, 31);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ProtectedCuboidRegion claim(final String id, final int minX, final int maxX) {
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(minX, 0, 0), BlockVector3.at(maxX, 255, 31));
        manager.addRegion(region);
        return region;
    }

    /**
     * A piston at x=14, inside the redstone room, pushing a block at x=16 that is inside the farm.
     */
    private boolean push() {
        final Block piston = world.getBlockAt(14, 64, 10);
        piston.setType(Material.PISTON);
        final Block moved = world.getBlockAt(16, 64, 10);
        moved.setType(Material.STONE);

        final BlockPistonExtendEvent event =
            new BlockPistonExtendEvent(piston, List.of(moved), BlockFace.EAST);
        listener.onExtend(event);
        return !event.isCancelled();
    }

    @Test
    void aPistonReachesIntoARegionThatSaysNothing() {
        assertTrue(push());
    }

    @Test
    void aPistonCannotReachIntoARegionThatDeniesPistons() {
        farm.setFlag(Flags.PISTONS, State.DENY);

        assertFalse(push());
    }

    @Test
    void aSharedDomainLetsThePistonThrough() {
        farm.setFlag(Flags.PISTONS, State.DENY);
        farm.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));
        redstoneRoom.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));

        assertTrue(push());
    }

    @Test
    void aDomainOnlyOneSideNamesChangesNothing() {
        farm.setFlag(Flags.PISTONS, State.DENY);
        redstoneRoom.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));

        assertFalse(push(), "the farm never agreed to it");
    }

    @Test
    void domainsThatDoNotMatchChangeNothing() {
        farm.setFlag(Flags.PISTONS, State.DENY);
        farm.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));
        redstoneRoom.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("pumpkins"));

        assertFalse(push());
    }

    @Test
    void aDomainDoesNotOpenThePistonsOwnRegion() {
        redstoneRoom.setFlag(Flags.PISTONS, State.DENY);
        redstoneRoom.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));
        farm.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("wheat"));

        assertFalse(push(), "a region that bans pistons bans its own");
    }
}
