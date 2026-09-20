package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ItemFrameMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Filling and turning an item frame, which reaches the frame without going through block-break or
 * the plain interact flag. Emptying one is a punch and belongs to entity-item-frame-destroy.
 */
class ItemFrameProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private InteractionListener listener;

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
        listener = new InteractionListener(container.createQuery(), new MessageService(plugin));
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

    private boolean rightClickFrame(final PlayerMock player, final int x, final int z) {
        final ItemFrame frame = new ItemFrameMock(server, UUID.randomUUID());
        frame.teleport(new Location(world, x, 64, z));
        final PlayerInteractEntityEvent event =
            new PlayerInteractEntityEvent(player, frame, EquipmentSlot.HAND);
        listener.onItemFrame(event);
        return !event.isCancelled();
    }

    @Test
    void aStrangerCannotFillAFrameInAClaim() {
        claim("plot");

        assertFalse(rightClickFrame(server.addPlayer(), 16, 16));
    }

    @Test
    void anOwnerCanFillAFrameInTheirClaim() {
        final PlayerMock owner = server.addPlayer();
        claim("plot").getOwners().addPlayer(owner.getUniqueId());

        assertTrue(rightClickFrame(owner, 16, 16));
    }

    @Test
    void wildernessFramesAreLeftAlone() {
        claim("plot");

        assertTrue(rightClickFrame(server.addPlayer(), 500, 500));
    }

    @Test
    void anAllowedFlagOpensFramesToEveryone() {
        claim("plot").setFlag(Flags.ITEM_FRAME_ROTATION, State.ALLOW);

        assertTrue(rightClickFrame(server.addPlayer(), 16, 16));
    }
}
