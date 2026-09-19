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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block protection end to end against a mock server: a region, a player and a real
 * {@link BlockBreakEvent}, asserting what the listener decides rather than what the engine resolves.
 */
class BuildProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private BuildProtectionListener listener;
    private MessageService messages;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");

        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 3\nmessages:\n  no-permission: \"<red>You cannot do that here.\"\n",
            StandardCharsets.UTF_8);

        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        messages = new MessageService(plugin);
        final RegionQuery query = container.createQuery();
        listener = new BuildProtectionListener(query, messages);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RegionManager manager() {
        final RegionManager manager = container.get(world);
        assertNotNull(manager, "the world's regions load at enable");
        return manager;
    }

    private ProtectedCuboidRegion claim(final String id) {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager().addRegion(region);
        return region;
    }

    private boolean breakBlock(final PlayerMock player, final int x, final int y, final int z) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        final BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBreak(event);
        return !event.isCancelled();
    }

    @Test
    void aStrangerCannotBreakBlocksInAClaim() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();

        assertFalse(breakBlock(stranger, 16, 64, 16));
    }

    @Test
    void anOwnerCanBreakBlocksInTheirClaim() {
        final PlayerMock owner = server.addPlayer();
        claim("plot").getOwners().addPlayer(owner.getUniqueId());

        assertTrue(breakBlock(owner, 16, 64, 16));
    }

    @Test
    void wildernessIsNotProtected() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();

        assertTrue(breakBlock(stranger, 500, 64, 500));
    }

    @Test
    void anExplicitBlockBreakAllowOpensTheRegionToEveryone() {
        claim("spawn").setFlag(Flags.BLOCK_BREAK, State.ALLOW);
        final PlayerMock stranger = server.addPlayer();

        assertTrue(breakBlock(stranger, 16, 64, 16));
    }

    @Test
    void aDenyBlockBreakListOverridesMembership() {
        final PlayerMock owner = server.addPlayer();
        final ProtectedCuboidRegion region = claim("plot");
        region.getOwners().addPlayer(owner.getUniqueId());
        region.setFlag(Flags.DENY_BLOCK_BREAK, java.util.Set.of(Material.STONE));

        assertFalse(breakBlock(owner, 16, 64, 16));
    }

    @Test
    void aDeniedPlayerIsToldOnceWithinTheCooldown() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();

        breakBlock(stranger, 16, 64, 16);
        breakBlock(stranger, 16, 64, 17);

        assertNotNull(stranger.nextMessage(), "the first denial explains itself");
        assertEquals(null, stranger.nextMessage(), "the second is suppressed by the cooldown");
    }

    @Test
    void theEventGateSkipsAWorldThatOptedOut() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();
        final org.bukkit.configuration.file.YamlConfiguration config =
            new org.bukkit.configuration.file.YamlConfiguration();
        config.set("worlds.world.events.disabled", java.util.List.of("BlockBreakEvent"));
        com.tricrotism.uworldguard.config.EventGate.load(config, server.getLogger());
        try {
            assertTrue(breakBlock(stranger, 16, 64, 16), "the listener does not act in this world");
        } finally {
            com.tricrotism.uworldguard.config.EventGate.load(
                new org.bukkit.configuration.file.YamlConfiguration(), server.getLogger());
        }
    }
}
