package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.service.*;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Pig;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.RegisteredListener;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Movement does only the work that changes something: riding a boat or a player leaves the server-wide
 * {@link EntityMoveEvent} unheard, a speed region matching the player's speed owes no restore, and a
 * reload that keeps the movement settings leaves the polls running.
 */
class MovementListenerTest {

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
    private RegionManager manager;
    private PendingRestores restores;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");

        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  entry-denied: \"<red>You may not enter.\"\n",
            StandardCharsets.UTF_8);

        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        manager = container.get(world);
        assertNotNull(manager);
        restores = new PendingRestores(plugin);

        player = server.addPlayer();
        player.setLocation(new Location(world, 100.5, 64, 100.5));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MovementListener listener(final Settings settings) {
        return new MovementListener(plugin, container.createQuery(), new MessageService(plugin),
            new CollisionService(plugin), new ChamberedPearlTracker(plugin), new ChatTags(), restores,
            new PlayerTickService(plugin, container, container.createQuery(),
                new ChunkUnloadService(plugin, container)), settings);
    }

    private void register() {
        server.getPluginManager().registerEvents(listener(new Settings()), plugin);
    }

    private ProtectedCuboidRegion spawn() {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion("spawn",
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(region);
        return region;
    }

    private boolean hearsEntityMoves() {
        for (final RegisteredListener registration : EntityMoveEvent.getHandlerList().getRegisteredListeners()) {
            if (registration.getPlugin() == plugin) {
                return true;
            }
        }
        return false;
    }

    @Test
    void ridingABoatLeavesEntityMoveEventUnheard() {
        register();
        final Boat boat = world.spawn(player.getLocation(), Boat.class);

        assertTrue(boat.addPassenger(player));
        server.getScheduler().performTicks(1);

        assertFalse(hearsEntityMoves(), "a boat never fires EntityMoveEvent");
    }

    @Test
    void ridingAPigListensForEntityMoveEvent() {
        register();
        final Pig pig = world.spawn(player.getLocation(), Pig.class);

        assertTrue(pig.addPassenger(player));
        server.getScheduler().performTicks(1);

        assertTrue(hearsEntityMoves());
    }

    @Test
    void ridingAPlayerLeavesEntityMoveEventUnheard() {
        register();
        final PlayerMock carrier = server.addPlayer();

        assertTrue(carrier.addPassenger(player));
        server.getScheduler().performTicks(1);

        assertFalse(hearsEntityMoves(), "a player fires PlayerMoveEvent, never EntityMoveEvent");
    }

    @Test
    void aSpeedRegionMatchingThePlayersSpeedOwesNothing() {
        final ProtectedCuboidRegion spawn = spawn();
        spawn.setFlag(Flags.WALK_SPEED, (double) player.getWalkSpeed());
        spawn.setFlag(Flags.FLY_SPEED, (double) player.getFlySpeed());
        register();

        assertTrue(player.teleport(new Location(world, 10.5, 64, 10.5)));
        server.getScheduler().performTicks(2);

        assertTrue(restores.outstanding().isEmpty(), "nothing was overridden, so nothing is owed");
    }

    @Test
    void aBypassingPlayerKeepsFlightInANoFlyRegion() {
        spawn().setFlag(Flags.FLY, false);
        player.setAllowFlight(true);
        player.addAttachment(plugin, Bypass.NODE, true);
        assertTrue(Bypass.toggle(player));
        register();

        try {
            assertTrue(player.teleport(new Location(world, 10.5, 64, 10.5)));
            server.getScheduler().performTicks(2);

            assertTrue(player.getAllowFlight(), "fly is a region restriction, so bypass lifts it");
        } finally {
            Bypass.clear(player.getUniqueId());
        }
    }

    @Test
    void togglingBypassInsideANoFlyRegionAppliesWithoutMoving() {
        spawn().setFlag(Flags.FLY, false);
        player.setAllowFlight(true);
        player.addAttachment(plugin, Bypass.NODE, true);
        final MovementListener movement = listener(new Settings());
        server.getPluginManager().registerEvents(movement, plugin);
        assertTrue(player.teleport(new Location(world, 10.5, 64, 10.5)));
        server.getScheduler().performTicks(2);
        assertFalse(player.getAllowFlight(), "the region takes flight away on entry");

        try {
            assertTrue(Bypass.toggle(player));
            movement.refresh(player);
            server.getScheduler().performTicks(1);
            assertTrue(player.getAllowFlight(), "arming bypass hands flight back in place");

            assertFalse(Bypass.toggle(player));
            movement.refresh(player);
            server.getScheduler().performTicks(1);
            assertFalse(player.getAllowFlight(), "disarming it takes flight away again in place");
        } finally {
            Bypass.clear(player.getUniqueId());
        }
    }

    @Test
    void aBypassingPlayerLeavingARegionRunsNoExitCommand() {
        final AtomicInteger runs = new AtomicInteger();
        server.getCommandMap().register("test", new Command("probe") {
            @Override
            public boolean execute(final CommandSender sender, final String label, final String[] args) {
                runs.incrementAndGet();
                return true;
            }
        });
        final ProtectedCuboidRegion spawn = spawn();
        spawn.setFlag(Flags.COMMAND_ON_EXIT, "probe");
        spawn.setFlag(Flags.CONSOLE_COMMAND_ON_EXIT, "probe");
        player.addAttachment(plugin, Bypass.NODE, true);
        register();
        assertTrue(player.teleport(new Location(world, 10.5, 64, 10.5)));
        server.getScheduler().performTicks(2);

        assertTrue(player.teleport(new Location(world, 100.5, 64, 100.5)));
        server.getScheduler().performTicks(2);
        assertEquals(2, runs.get(), "an ordinary player runs both exit commands");
        assertTrue(player.teleport(new Location(world, 10.5, 64, 10.5)));
        server.getScheduler().performTicks(2);

        try {
            assertTrue(Bypass.toggle(player));
            assertTrue(player.teleport(new Location(world, 100.5, 64, 100.5)));
            server.getScheduler().performTicks(2);

            assertEquals(2, runs.get(), "bypass skips both exit commands");
        } finally {
            Bypass.clear(player.getUniqueId());
        }
    }

    @Test
    void aCrossingReachesStaffOnlyOnceTheyHoldTheNotifyPermission() throws Exception {
        Files.writeString(new File(plugin.getDataFolder(), "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  notify-enter: \"<gray><player> entered <region>\"\n",
            StandardCharsets.UTF_8);
        spawn().setFlag(Flags.NOTIFY_ENTER, true);
        final PlayerTickService ticks = new PlayerTickService(plugin, container, container.createQuery(),
            new ChunkUnloadService(plugin, container));
        final MovementListener movement = new MovementListener(plugin, container.createQuery(),
            new MessageService(plugin), new CollisionService(plugin), new ChamberedPearlTracker(plugin),
            new ChatTags(), restores, ticks, new Settings());
        server.getPluginManager().registerEvents(ticks, plugin);
        movement.start();
        final PlayerMock staff = server.addPlayer();
        final Location outside = new Location(world, 100.5, 64, 100.5);
        final Location inside = new Location(world, 10.5, 64, 10.5);
        server.getScheduler().performTicks(20);

        movement.onMove(new PlayerMoveEvent(player, outside, inside));
        server.getScheduler().performTicks(1);
        assertNull(staff.nextComponentMessage(), "no permission, no announcement");

        staff.addAttachment(plugin, PlayerTickService.NOTIFY_PERMISSION, true);
        server.getScheduler().performTicks(20);
        movement.onMove(new PlayerMoveEvent(player, inside, outside));
        movement.onMove(new PlayerMoveEvent(player, outside, inside));
        server.getScheduler().performTicks(1);
        assertNotNull(staff.nextComponentMessage(), "staff hear the crossing within a second of holding it");
        assertNull(player.nextComponentMessage(), "the mover is not staff");
    }

    @Test
    void aFullRegionRefusesEntryAndOneTheWorldCannotFillDoesNot() {
        final ProtectedCuboidRegion region = spawn();
        final MovementListener movement = listener(new Settings());
        final PlayerMock inside = server.addPlayer();
        inside.setLocation(new Location(world, 5.5, 64, 5.5));
        final Location from = new Location(world, 100.5, 64, 100.5);
        final Location to = new Location(world, 10.5, 64, 10.5);

        region.setFlag(Flags.PLAYER_COUNT_LIMIT, 1);
        final PlayerMoveEvent full = new PlayerMoveEvent(player, from, to);
        movement.onMove(full);
        assertTrue(full.isCancelled(), "one inside a limit of one is full");

        region.setFlag(Flags.PLAYER_COUNT_LIMIT, 2);
        final PlayerMoveEvent room = new PlayerMoveEvent(player, from, to);
        movement.onMove(room);
        assertFalse(room.isCancelled(), "one inside a limit of two has room");

        region.setFlag(Flags.PLAYER_COUNT_LIMIT, 5);
        final PlayerMoveEvent spare = new PlayerMoveEvent(player, from, to);
        movement.onMove(spare);
        assertFalse(spare.isCancelled(), "two players in the world cannot fill five");
    }

    @Test
    void aReloadThatChangesNothingKeepsThePollAndItsPosition() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("movement.mode", "TASK");
        final Settings settings = new Settings();
        settings.load(config);
        final MovementListener movement = listener(settings);
        movement.start();
        server.getScheduler().performTicks(settings.movementTaskTicks());
        spawn().setFlag(Flags.GREETING, "Welcome to spawn");

        // MockBukkit's task cancel throws, so cancelling the polls fails here instead of aborting the test.
        assertDoesNotThrow(() -> movement.applySettings(settings), "unchanged settings leave the polls running");
        player.setLocation(new Location(world, 10.5, 64, 10.5));
        server.getScheduler().performTicks(settings.movementTaskTicks());

        assertNotNull(player.nextComponentMessage(), "the first crossing after the reload is still announced");
    }
}
