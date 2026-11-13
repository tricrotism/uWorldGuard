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
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ItemMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

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
        listener = new BuildProtectionListener(query, messages, true);
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

    private boolean placeBlock(final PlayerMock player, final Material type, final int x, final int y, final int z) {
        final Block block = world.getBlockAt(x, y, z);
        final BlockState before = block.getState();
        block.setType(type);
        final BlockPlaceEvent event = new BlockPlaceEvent(block, before, world.getBlockAt(x, y - 1, z),
            new ItemStack(type), player, true, EquipmentSlot.HAND);
        listener.onPlace(event);
        return !event.isCancelled();
    }

    @Test
    void thePlaceListsJudgeWhatWasPlaced() {
        final PlayerMock owner = server.addPlayer();
        final PlayerMock stranger = server.addPlayer();
        final ProtectedCuboidRegion region = claim("plot");
        region.getOwners().addPlayer(owner.getUniqueId());
        region.setFlag(Flags.DENY_BLOCK_PLACE, java.util.Set.of(Material.TNT));
        region.setFlag(Flags.ALLOW_BLOCK_PLACE, java.util.Set.of(Material.TORCH));

        assertFalse(placeBlock(owner, Material.TNT, 16, 64, 16), "a denied block is refused even to the owner");
        assertTrue(placeBlock(owner, Material.STONE, 16, 64, 17));
        assertTrue(placeBlock(stranger, Material.TORCH, 16, 64, 18), "an allowed block opens the claim");
        assertFalse(placeBlock(stranger, Material.STONE, 16, 64, 19));
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

    private boolean step(final PlayerMock player, final int x, final int y, final int z) {
        final Block plate = world.getBlockAt(x, y, z);
        plate.setType(Material.STONE_PRESSURE_PLATE);
        final PlayerInteractEvent event = new PlayerInteractEvent(player, Action.PHYSICAL, null, plate, BlockFace.SELF);
        listener.onStep(event);
        return !event.isCancelled();
    }

    @Test
    void aStrangerCannotTriggerAPressurePlateInAClaim() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();

        assertFalse(step(stranger, 16, 64, 16));
        assertNull(stranger.nextMessage(), "standing on a plate repeats every tick, so nothing is sent");
    }

    @Test
    void aMemberCanTriggerAPressurePlateInTheirClaim() {
        final PlayerMock member = server.addPlayer();
        claim("plot").getMembers().addPlayer(member.getUniqueId());

        assertTrue(step(member, 16, 64, 16));
    }

    @Test
    void anItemAStrangerThrewCannotTriggerAPlateInAClaim() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();
        final Block plate = world.getBlockAt(16, 64, 16);
        plate.setType(Material.OAK_PRESSURE_PLATE);
        final UUID thrower = stranger.getUniqueId();
        final ItemMock item = new ItemMock(server, UUID.randomUUID(), new ItemStack(Material.STONE)) {
            @Override
            public UUID getThrower() {
                return thrower;
            }
        };
        item.setLocation(plate.getLocation());
        final EntityInteractEvent event = new EntityInteractEvent(item, plate);

        listener.onEntityStep(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aBedWhoseHeadLandsInAClaimIsRefused() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();
        final Block foot = world.getBlockAt(-1, 64, 5);
        final Block head = world.getBlockAt(0, 64, 5);
        final BlockState footBefore = foot.getState();
        final BlockState headBefore = head.getState();
        foot.setType(Material.RED_BED);
        head.setType(Material.RED_BED);
        final BlockMultiPlaceEvent event = new BlockMultiPlaceEvent(List.of(footBefore, headBefore),
            world.getBlockAt(-1, 63, 5), new ItemStack(Material.RED_BED), stranger, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertTrue(event.isCancelled(), "the foot is in the wilderness but the head is in the claim");
    }

    @Test
    void aBedOutsideEveryClaimIsAllowed() {
        claim("plot");
        final PlayerMock stranger = server.addPlayer();
        final Block foot = world.getBlockAt(-2, 64, 5);
        final Block head = world.getBlockAt(-1, 64, 5);
        final BlockState footBefore = foot.getState();
        final BlockState headBefore = head.getState();
        foot.setType(Material.RED_BED);
        head.setType(Material.RED_BED);
        final BlockMultiPlaceEvent event = new BlockMultiPlaceEvent(List.of(footBefore, headBefore),
            world.getBlockAt(-2, 63, 5), new ItemStack(Material.RED_BED), stranger, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertFalse(event.isCancelled());
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
