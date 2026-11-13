package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Punching a dragon egg teleports it, so a region that denies interaction must refuse the punch.
 */
class DragonEggTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private BuildProtectionListener build;
    private ProtectedCuboidRegion claim;

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
        build = new BuildProtectionListener(container.createQuery(), new MessageService(plugin), true);

        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        claim = new ProtectedCuboidRegion("claim", BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(claim);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerInteractEvent punch(final Material type) {
        final Block block = world.getBlockAt(16, 64, 16);
        block.setType(type);
        final PlayerInteractEvent event = new PlayerInteractEvent(server.addPlayer(), Action.LEFT_CLICK_BLOCK,
            new ItemStack(Material.AIR), block, BlockFace.UP, EquipmentSlot.HAND);
        build.onPunchEgg(event);
        return event;
    }

    @Test
    void aStrangerCannotPunchTheEgg() {
        claim.setFlag(Flags.INTERACT, State.DENY);

        assertEquals(Event.Result.DENY, punch(Material.DRAGON_EGG).useInteractedBlock());
    }

    @Test
    void anUnprotectedClaimRefusesAStrangerToo() {
        assertEquals(Event.Result.DENY, punch(Material.DRAGON_EGG).useInteractedBlock());
    }

    @Test
    void interactAllowLetsTheEggMove() {
        claim.setFlag(Flags.INTERACT, State.ALLOW);

        assertNotEquals(Event.Result.DENY, punch(Material.DRAGON_EGG).useInteractedBlock());
    }

    @Test
    void punchingOtherBlocksIsLeftToBlockBreak() {
        claim.setFlag(Flags.INTERACT, State.DENY);

        assertNotEquals(Event.Result.DENY, punch(Material.STONE).useInteractedBlock());
    }
}
