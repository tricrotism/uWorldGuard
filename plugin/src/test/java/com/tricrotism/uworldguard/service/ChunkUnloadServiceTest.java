package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * chunk-unload deny holds the chunks players stood in, never loads one itself, and does not spread
 * to neighbours.
 */
class ChunkUnloadServiceTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    /**
     * MockBukkit leaves plugin chunk tickets unimplemented. As on Paper, adding one loads the chunk.
     */
    private static final class TicketWorld extends WorldMock {

        private final Map<Long, Set<Plugin>> tickets = new HashMap<>();

        @Override
        public boolean addPluginChunkTicket(final int x, final int z, final Plugin plugin) {
            getChunkAt(x, z);
            return tickets.computeIfAbsent(key(x, z), _ -> new HashSet<>()).add(plugin);
        }

        @Override
        public boolean removePluginChunkTicket(final int x, final int z, final Plugin plugin) {
            final Set<Plugin> holders = tickets.get(key(x, z));
            return holders != null && holders.remove(plugin);
        }

        @Override
        public Collection<Plugin> getPluginChunkTickets(final int x, final int z) {
            return tickets.getOrDefault(key(x, z), Set.of());
        }

        private static long key(final int x, final int z) {
            return ((long) x << 32) | (z & 0xffffffffL);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void onlyChunksPlayersStoodInAreHeldUntilTheRegionStopsDenying() {
        final ServerMock server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        final TicketWorld world = new TicketWorld();
        world.setName("world");
        server.addWorld(world);
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion spawn =
            new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(47, 255, 15));
        spawn.setFlag(Flags.CHUNK_UNLOAD, State.DENY);
        manager.addRegion(spawn);
        world.getChunkAt(0, 0);

        final ChunkUnloadService service = new ChunkUnloadService(plugin, container);
        service.start();
        server.getScheduler().performTicks(101);

        assertTrue(world.getPluginChunkTickets(0, 0).isEmpty(), "a loaded chunk nobody stood in is not held");
        assertFalse(world.isChunkLoaded(2, 0), "the reconcile loads nothing");

        final PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 24.5, 64, 8.5));
        service.visit(player);
        assertTrue(world.getPluginChunkTickets(1, 0).contains(plugin), "the chunk a player stood in is held");
        assertTrue(world.getPluginChunkTickets(0, 0).isEmpty(), "its neighbours are not");
        assertTrue(world.getPluginChunkTickets(2, 0).isEmpty(), "its neighbours are not");

        player.teleport(new Location(world, 88.5, 64, 8.5));
        service.visit(player);
        assertTrue(world.getPluginChunkTickets(5, 0).isEmpty(), "a chunk outside the region is not held");

        spawn.setFlag(Flags.CHUNK_UNLOAD, null);
        server.getScheduler().performTicks(100);
        assertTrue(world.getPluginChunkTickets(1, 0).isEmpty(), "the ticket goes when the region stops denying");
    }
}
