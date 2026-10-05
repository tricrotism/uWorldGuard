package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.service.ChamberedPearlTracker;
import com.tricrotism.uworldguard.service.CollisionService;
import com.tricrotism.uworldguard.service.PendingRestores;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
 * The arena setup from a live report: a global region that denies build, a PvP region nobody is a
 * member of, and {@code game-mode} regions around it. Each test is one line of that report.
 */
class ArenaReportTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;
    private RegionContainerImpl container;
    private BuildProtectionListener build;
    private WorkbenchListener workbench;
    private PlayerStateListener state;
    private MovementListener movement;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");

        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 3\nmessages:\n  no-permission: \"<red>You cannot do that here.\"\n",
            StandardCharsets.UTF_8);

        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final MessageService messages = new MessageService(plugin);
        final RegionQuery query = container.createQuery();
        build = new BuildProtectionListener(query, messages);
        workbench = new WorkbenchListener(query, messages);
        state = new PlayerStateListener(query, messages);
        movement = new MovementListener(plugin, query, messages, new CollisionService(plugin),
            new ChamberedPearlTracker(plugin), new ChatTags(), new PendingRestores(plugin), new Settings());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RegionManager manager() {
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        return manager;
    }

    private ProtectedRegion global() {
        final ProtectedRegion global = manager().getRegion("__global__");
        assertNotNull(global);
        return global;
    }

    private ProtectedCuboidRegion region(final String id, final int min, final int max) {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(min, 0, min), BlockVector3.at(max, 255, max));
        manager().addRegion(region);
        return region;
    }

    private void arena() {
        global().setFlag(Flags.BUILD, State.DENY);
        region("arenapvp_unsafe", 0, 32).setFlag(Flags.CHEST_ACCESS, State.DENY);
    }

    private PlayerInteractEvent rightClick(final PlayerMock player, final Material block, final Material held) {
        final Block clicked = world.getBlockAt(16, 64, 16);
        clicked.setType(block);
        final PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
            new ItemStack(held), clicked, BlockFace.UP, EquipmentSlot.HAND);
        build.onInteract(event);
        if (event.useInteractedBlock() != Event.Result.DENY) {
            workbench.onOpen(event);
        }
        return event;
    }

    private void walk(final PlayerMock player, final int fromX, final int toX) {
        final Location from = new Location(world, fromX, 64, fromX);
        final Location to = new Location(world, toX, 64, toX);
        player.setLocation(to);
        movement.onMove(new PlayerMoveEvent(player, from, to));
    }

    @Test
    void anXpBottleThrownAtTheFloorStillThrows() {
        arena();

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.STONE, Material.EXPERIENCE_BOTTLE);

        assertNotEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void aWindChargeThrownAtTheFloorStillThrows() {
        arena();

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.STONE, Material.WIND_CHARGE);

        assertNotEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void anEnderChestOpensWhereBuildIsDenied() {
        arena();

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.ENDER_CHEST, Material.AIR);

        assertNotEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    void aChestStaysShutWhereBuildIsDenied() {
        arena();

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.CHEST, Material.AIR);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    void aModeChosenInsideAGameModeRegionSticksAndIsNotBroughtBackOnLeaving() {
        region("lobby", 0, 32).setFlag(Flags.GAME_MODE, "survival");
        final PlayerMock staff = server.addPlayer();
        walk(staff, 100, 10);

        staff.setGameMode(GameMode.SPECTATOR);
        walk(staff, 10, 11);
        assertEquals(GameMode.SPECTATOR, staff.getGameMode(), "/gmsp inside the region holds");

        staff.setGameMode(GameMode.SURVIVAL);
        walk(staff, 11, 100);
        assertEquals(GameMode.SURVIVAL, staff.getGameMode(), "the arena sets no game-mode");
    }

    @Test
    void enteringSetsTheModeAndLeavingRestoresIt() {
        region("spawn", 0, 32).setFlag(Flags.GAME_MODE, "adventure");
        final PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);

        walk(player, 100, 10);
        assertEquals(GameMode.ADVENTURE, player.getGameMode());

        walk(player, 10, 100);
        assertEquals(GameMode.CREATIVE, player.getGameMode());
    }

    @Test
    void anExplicitDenyStillShutsAnEnderChest() {
        arena();
        global().setFlag(Flags.PERMIT_WORKBENCHES, State.DENY);

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.ENDER_CHEST, Material.AIR);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
    }

    @Test
    void disableCompletelyStillStopsAThrowableAimedAtTheFloor() {
        arena();
        global().setFlag(Flags.DISABLE_COMPLETELY, java.util.Set.of(Material.ENDER_PEARL));
        server.getPluginManager().registerEvents(build, plugin);
        server.getPluginManager().registerEvents(
            new ItemUseListener(container, container.createQuery(), new MessageService(plugin)), plugin);
        final Block floor = world.getBlockAt(16, 64, 16);
        floor.setType(Material.STONE);
        final PlayerInteractEvent event = new PlayerInteractEvent(server.addPlayer(), Action.RIGHT_CLICK_BLOCK,
            new ItemStack(Material.ENDER_PEARL), floor, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        assertEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void placingABlockOnTheFloorOfABlockPlaceRegionIsNotAnInteraction() {
        global().setFlag(Flags.BUILD, State.DENY);
        region("crystalpvp", 0, 32).setFlag(Flags.BLOCK_PLACE, State.ALLOW);

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.STONE, Material.OBSIDIAN);

        assertFalse(event.isCancelled(), "clicking stone uses nothing, so only block-place decides");
        assertNotEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void aWindChargeStillThrowsWhereInteractIsDenied() {
        region("swordpvp", 0, 32).setFlag(Flags.INTERACT, State.DENY);

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.STONE, Material.WIND_CHARGE);

        assertNotEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void aDeniedDoorKeepsTheBlockInHandUsable() {
        final ProtectedCuboidRegion arena = region("crystalpvp", 0, 32);
        arena.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
        arena.setFlag(Flags.USE, State.DENY);

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.OAK_DOOR, Material.OBSIDIAN);

        assertEquals(Event.Result.DENY, event.useInteractedBlock(), "the door stays shut");
        assertNotEquals(Event.Result.DENY, event.useItemInHand(), "the block can still be placed against it");
    }

    @Test
    void aHoeOnTheFloorIsStillRefused() {
        arena();

        final PlayerInteractEvent event = rightClick(server.addPlayer(), Material.DIRT, Material.IRON_HOE);

        assertEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void aBypassingPlayerKeepsTheGameModeTheyChose() {
        region("spawn", 0, 32).setFlag(Flags.GAME_MODE, "adventure");
        final PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, Bypass.NODE, true);
        assertTrue(Bypass.toggle(staff));
        try {
            staff.setGameMode(GameMode.SURVIVAL);

            walk(staff, 10, 11);

            assertEquals(GameMode.SURVIVAL, staff.getGameMode());
        } finally {
            Bypass.clear(staff.getUniqueId());
        }
    }

    @Test
    void fallDamageLandsWhereOnlyGameModeIsSet() {
        global().setFlag(Flags.GAME_MODE, "survival");
        final PlayerMock player = server.addPlayer();
        final EntityDamageEvent event = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL,
            DamageSource.builder(DamageType.FALL).build(), 4.0);

        state.onDamage(event);

        assertFalse(event.isCancelled());
    }
}
