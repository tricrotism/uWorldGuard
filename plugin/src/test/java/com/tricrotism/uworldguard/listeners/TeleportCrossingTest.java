package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.service.*;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
 * In EVENT mode a teleport or respawn is a crossing like any other: entry is enforced, and the
 * destination's greeting and player state apply once the player lands.
 */
class TeleportCrossingTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionManager manager;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");

        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  entry-denied: \"<red>You may not enter.\"\n",
            StandardCharsets.UTF_8);

        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        manager = container.get(world);
        assertNotNull(manager);

        final MovementListener movement = new MovementListener(plugin, container.createQuery(),
            new MessageService(plugin), new CollisionService(plugin), new ChamberedPearlTracker(plugin),
            new ChatTags(), new PendingRestores(plugin),
            new PlayerTickService(plugin, container, container.createQuery(),
                new ChunkUnloadService(plugin, container)), new Settings());
        server.getPluginManager().registerEvents(movement, plugin);

        player = server.addPlayer();
        player.setLocation(new Location(world, 100, 64, 100));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ProtectedCuboidRegion region(final String id) {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(region);
        return region;
    }

    private static Location inside(final WorldMock world) {
        return new Location(world, 10, 64, 10);
    }

    @Test
    void aTeleportIntoAnEntryDenyRegionIsCancelled() {
        region("vault").setFlag(Flags.ENTRY, State.DENY);

        assertFalse(player.teleport(inside(world)));
        assertEquals(100, player.getLocation().getBlockX());
    }

    @Test
    void aMemberMayTeleportIntoAnEntryDenyRegion() {
        final ProtectedCuboidRegion vault = region("vault");
        vault.setFlag(Flags.ENTRY, State.DENY);
        vault.getMembers().addPlayer(player.getUniqueId());

        assertTrue(player.teleport(inside(world)));
        assertEquals(10, player.getLocation().getBlockX());
    }

    @Test
    void teleportingInSendsTheGreetingOnceLanded() {
        region("spawn").setFlag(Flags.GREETING, "Welcome to spawn");

        assertTrue(player.teleport(inside(world)));
        server.getScheduler().performTicks(2);

        final Component greeting = player.nextComponentMessage();
        assertNotNull(greeting, "the greeting should be sent after the teleport lands");
        assertEquals("Welcome to spawn", PlainTextComponentSerializer.plainText().serialize(greeting));
    }

    @Test
    void teleportingInAndOutAppliesAndRestoresTheGameMode() {
        region("spawn").setFlag(Flags.GAME_MODE, "adventure");
        player.setGameMode(GameMode.CREATIVE);

        assertTrue(player.teleport(inside(world)));
        server.getScheduler().performTicks(2);
        assertEquals(GameMode.ADVENTURE, player.getGameMode());

        assertTrue(player.teleport(new Location(world, 100, 64, 100)));
        server.getScheduler().performTicks(2);
        assertEquals(GameMode.CREATIVE, player.getGameMode());
    }

    @Test
    void respawningInsideAGameModeRegionAppliesIt() {
        region("spawn").setFlag(Flags.GAME_MODE, "adventure");
        world.setSpawnLocation(10, 64, 10);
        player.setGameMode(GameMode.CREATIVE);

        player.setHealth(0);
        player.respawn();
        server.getScheduler().performTicks(2);

        assertEquals(10, player.getLocation().getBlockX());
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
    }
}
