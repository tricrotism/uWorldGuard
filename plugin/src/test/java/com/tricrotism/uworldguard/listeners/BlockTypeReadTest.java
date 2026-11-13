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
import org.bukkit.Location;
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
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A refused click or step reads the clicked block's type from the world once, however many checks use it.
 */
class BlockTypeReadTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private BuildProtectionListener build;
    private WorkbenchListener workbench;
    private ProtectedCuboidRegion claim;
    private int typeReads;

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
        final MessageService messages = new MessageService(plugin);
        final RegionQuery query = container.createQuery();
        build = new BuildProtectionListener(query, messages, true);
        workbench = new WorkbenchListener(container, query, messages);

        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        claim = new ProtectedCuboidRegion("claim", BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(claim);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block counted(final Material type) {
        return new BlockMock(type, new Location(world, 16, 64, 16)) {
            @Override
            public Material getType() {
                typeReads++;
                return super.getType();
            }
        };
    }

    private PlayerInteractEvent rightClick(final Block block) {
        final PlayerInteractEvent event = new PlayerInteractEvent(server.addPlayer(), Action.RIGHT_CLICK_BLOCK,
            new ItemStack(Material.AIR), block, BlockFace.UP, EquipmentSlot.HAND);
        typeReads = 0;
        return event;
    }

    @Test
    void aRefusedClickReadsTheTypeOnce() {
        final PlayerInteractEvent event = rightClick(counted(Material.OAK_DOOR));

        build.onInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock(), "a stranger cannot open a door in the claim");
        assertEquals(1, typeReads, "usesBlock, the whitelist and explicitlyAllowed share one read");
    }

    @Test
    void aRefusedStepReadsTheTypeOnce() {
        final PlayerInteractEvent event = new PlayerInteractEvent(server.addPlayer(), Action.PHYSICAL, null,
            counted(Material.STONE_PRESSURE_PLATE), BlockFace.SELF);
        typeReads = 0;

        build.onStep(event);

        assertTrue(event.isCancelled(), "a stranger cannot trigger a plate in the claim");
        assertEquals(1, typeReads, "the trigger test and the whitelist share one read");
    }

    @Test
    void aRefusedWorkbenchReadsTheTypeOnce() {
        claim.setFlag(Flags.PERMIT_WORKBENCHES, State.DENY);
        final PlayerInteractEvent event = rightClick(counted(Material.CRAFTING_TABLE));

        workbench.onOpen(event);

        assertTrue(event.isCancelled());
        assertEquals(1, typeReads, "the workbench, anvil and allow-interact tests share one read");
    }
}
