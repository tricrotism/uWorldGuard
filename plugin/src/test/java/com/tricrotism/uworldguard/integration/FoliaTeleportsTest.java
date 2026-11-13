package com.tricrotism.uworldguard.integration;

import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.listeners.MovementListener;
import com.tricrotism.uworldguard.listeners.PlayerStateListener;
import com.tricrotism.uworldguard.listeners.TravelListener;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.service.*;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.EnderPearlMock;
import org.mockbukkit.mockbukkit.entity.PigMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain Folia fires no teleport or portal event for pearls, chorus fruit or portals, so these are
 * judged from the events that lead up to them.
 */
class FoliaTeleportsTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private ProtectedCuboidRegion spawn;
    private FoliaTeleports folia;
    private int locationReads;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        server.addSimpleWorld("world_nether").setEnvironment(World.Environment.NETHER);
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final RegionQuery query = container.createQuery();
        final MessageService messages = new MessageService(plugin);
        final MovementListener movement = new MovementListener(plugin, query, messages, new CollisionService(plugin),
            new ChamberedPearlTracker(plugin), new ChatTags(), new PendingRestores(plugin),
            new PlayerTickService(plugin, container, query, new ChunkUnloadService(plugin, container)),
            new Settings());
        folia = new FoliaTeleports(new PlayerStateListener(query, messages), movement, new TravelListener(query),
            container);
        player = server.addPlayer();

        spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(-100, 0, -100), BlockVector3.at(100, 255, 100));
        manager.addRegion(spawn);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location at(final int x) {
        return new Location(world, x + 0.5, 64, 5.5);
    }

    private EnderPearlMock pearlLandingAt(final int x) {
        final EnderPearlMock pearl = new EnderPearlMock(server, UUID.randomUUID());
        pearl.setLocation(at(x));
        pearl.setShooter(player);
        return pearl;
    }

    private PigMock riderlessPig() {
        final PigMock pig = new PigMock(server, UUID.randomUUID()) {
            @Override
            public Location getLocation() {
                locationReads++;
                return super.getLocation();
            }
        };
        pig.setLocation(at(5));
        locationReads = 0;
        return pig;
    }

    @Test
    void aPearlThrownOutOfARegionDenyingPearlsIsStoppedWhereItHits() {
        spawn.setFlag(Flags.ENDERPEARL, State.DENY);
        player.setLocation(at(5));
        final EnderPearlMock pearl = pearlLandingAt(200);
        final ProjectileHitEvent event = new ProjectileHitEvent(pearl);

        folia.onPearlHit(event);

        assertTrue(event.isCancelled());
        assertTrue(pearl.isDead(), "a cancelled hit alone lets the pearl fly on");
    }

    @Test
    void aPearlWhereNoRegionDeniesPearlsLands() {
        spawn.setFlag(Flags.ENDERPEARL, State.DENY);
        player.setLocation(at(200));
        final ProjectileHitEvent event = new ProjectileHitEvent(pearlLandingAt(210));

        folia.onPearlHit(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void chorusFruitIsNotEatenInARegionDenyingIt() {
        spawn.setFlag(Flags.CHORUS_TELEPORT, State.DENY);
        player.setLocation(at(5));
        final PlayerItemConsumeEvent event = new PlayerItemConsumeEvent(player, new ItemStack(Material.CHORUS_FRUIT),
            EquipmentSlot.HAND);

        folia.onChorus(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aPortalInARegionDenyingPortalsHoldsThePlayer() {
        spawn.setFlag(Flags.NETHER_PORTALS, State.DENY);
        player.setLocation(at(5));
        final EntityPortalEnterEvent event = new EntityPortalEnterEvent(player, at(5), PortalType.NETHER);

        folia.onPortalEnter(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aPortalInARegionDenyingPortalsHoldsAPlayersMount() {
        spawn.setFlag(Flags.NETHER_PORTALS, State.DENY);
        player.setLocation(at(5));
        final Pig pig = world.spawn(at(5), Pig.class);
        assertTrue(pig.addPassenger(player));
        final EntityPortalEnterEvent event = new EntityPortalEnterEvent(pig, at(5), PortalType.NETHER);

        folia.onPortalEnter(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aRiderlessMobInAPortalIsNotLocated() {
        final EntityPortalEnterEvent event = new EntityPortalEnterEvent(riderlessPig(), at(5), PortalType.NETHER);

        folia.onPortalEnter(event);

        assertFalse(event.isCancelled());
        assertEquals(0, locationReads, "nothing can refuse a riderless mob, so its position is never read");
    }

    @Test
    void aPortalOutOfARegionThatHoldsItsPlayersHoldsThem() {
        spawn.setFlag(Flags.EXIT, State.DENY);
        spawn.setFlag(Flags.EXIT_VIA_TELEPORT, State.DENY);
        player.setLocation(at(5));
        final EntityPortalEnterEvent event = new EntityPortalEnterEvent(player, at(5), PortalType.NETHER);

        folia.onPortalEnter(event);

        assertTrue(event.isCancelled());
    }
}
