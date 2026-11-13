package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A movement poll sharing the player's task runs on its own interval and the per-second work keeps its
 * own, even when the poll interval does not divide a second.
 */
class PlayerTickServiceTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aSharedPollKeepsItsOwnTicksAndTheSecondKeepsItsOwn() {
        final ServerMock server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        final WorldMock world = server.addSimpleWorld("world");
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion locked =
            new ProtectedCuboidRegion("spawn", BlockVector3.at(-32, 0, -32), BlockVector3.at(32, 255, 32));
        locked.setFlag(Flags.TIME_LOCK, "11750");
        manager.addRegion(locked);
        final PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));

        final AtomicInteger polls = new AtomicInteger();
        final List<Integer> sweeps = new ArrayList<>();
        final PlayerTickService service = new PlayerTickService(plugin, container, container.createQuery(),
            new ChunkUnloadService(plugin, container));
        service.start(new PlayerTickService.Poll((_, sweep) -> {
            final int poll = polls.incrementAndGet();
            if (sweep) {
                sweeps.add(poll);
            }
        }, 3, 7));

        server.getScheduler().performTicks(19);
        assertEquals(6, polls.get(), "polls at ticks 3 to 18");
        assertTrue(player.isPlayerTimeRelative(), "the per-second work has not run before tick 20");

        server.getScheduler().performTicks(1);
        assertEquals(11750L, player.getPlayerTime(), "the per-second work runs on tick 20");

        server.getScheduler().performTicks(22);
        assertEquals(14, polls.get(), "polls every 3 ticks up to tick 42");
        assertEquals(List.of(7, 14), sweeps, "every 7th poll sweeps");
    }

    @Test
    void aWatchListsHoldersOnlyWhileItIsActive() {
        final ServerMock server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        server.addSimpleWorld("world");
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final PlayerTickService service = new PlayerTickService(plugin, container, container.createQuery(),
            new ChunkUnloadService(plugin, container));
        final AtomicBoolean active = new AtomicBoolean();
        final Set<UUID> holders = service.watch("test.notify", active::get);
        final PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, "test.notify", true);
        final PlayerMock other = server.addPlayer();
        service.start(null);

        server.getScheduler().performTicks(20);
        assertTrue(holders.isEmpty(), "nothing is tested while the watch is idle");

        active.set(true);
        server.getScheduler().performTicks(20);
        assertEquals(Set.of(staff.getUniqueId()), holders, "only the holder is listed");
        assertFalse(holders.contains(other.getUniqueId()));
    }

    @Test
    void theTaskPeriodDividesBothTheIntervalAndASecond() {
        assertEquals(4, PlayerTickService.gcd(4, 20));
        assertEquals(1, PlayerTickService.gcd(3, 20));
        assertEquals(10, PlayerTickService.gcd(30, 20));
        assertEquals(20, PlayerTickService.gcd(20, 20));
    }
}
