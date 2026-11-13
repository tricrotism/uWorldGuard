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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CowMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pvp arena at x 0..15 beside a safezone at x 16..31 that denies pvp. A player in the safezone
 * cannot act on the arena, and the wilderness past x 32 is unaffected.
 */
class SafezoneIsolationTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private static final double ARENA = 8.5;
    private static final double SAFE = 20.5;
    private static final double WILD = 40.5;

    private ServerMock server;
    private WorldMock world;
    private SafezoneListener listener;
    private ProtectedCuboidRegion safe;

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
        listener = new SafezoneListener(container.createQuery(), new MessageService(plugin));

        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion arena =
            new ProtectedCuboidRegion("arena", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 31));
        safe = new ProtectedCuboidRegion("safe", BlockVector3.at(16, 0, 0), BlockVector3.at(31, 255, 31));
        arena.setFlag(Flags.PVP, State.ALLOW);
        safe.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(arena);
        manager.addRegion(safe);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location at(final double x) {
        return new Location(world, x, 64, 8.5);
    }

    private PlayerMock playerAt(final double x) {
        final PlayerMock player = server.addPlayer();
        player.setLocation(at(x));
        return player;
    }

    private boolean placeAllowed(final double from, final double to) {
        final Block placed = world.getBlockAt(at(to));
        final BlockPlaceEvent event = new BlockPlaceEvent(placed, placed.getState(), placed.getRelative(BlockFace.DOWN),
            new ItemStack(Material.STONE), playerAt(from), true, EquipmentSlot.HAND);
        listener.onPlace(event);
        return !event.isCancelled();
    }

    @Test
    void aSafezonePlayerCannotBuildInTheArena() {
        assertFalse(placeAllowed(SAFE, ARENA));
    }

    @Test
    void aPlayerInTheArenaBuildsThere() {
        assertTrue(placeAllowed(ARENA, ARENA));
    }

    @Test
    void theWildernessIsNotAPvpZone() {
        assertTrue(placeAllowed(SAFE, WILD), "only a region that sets pvp to allow is isolated");
    }

    @Test
    void aRegionThatDoesNotDenyPvpIsNotASafezone() {
        safe.setFlag(Flags.PVP, null);

        assertTrue(placeAllowed(SAFE, ARENA));
    }

    @Test
    @SuppressWarnings("removal")
    void aSafezonePlayerCannotHitAMobInTheArena() {
        final CowMock cow = new CowMock(server, UUID.randomUUID());
        cow.teleport(at(ARENA));
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(playerAt(SAFE), cow,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.GENERIC).build(), 4.0);

        listener.onDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void anArrowFromTheSafezoneIsRemovedOnHittingTheArena() {
        final Player shooter = playerAt(SAFE);
        final Arrow arrow = world.spawn(at(ARENA), Arrow.class);
        arrow.setShooter(shooter);
        final ProjectileHitEvent event = new ProjectileHitEvent(arrow, null, world.getBlockAt(at(ARENA)), BlockFace.UP);

        listener.onProjectileHit(event);

        assertTrue(event.isCancelled());
        assertTrue(arrow.isDead(), "a cancelled hit would otherwise let the arrow pass through the block");
    }
}
