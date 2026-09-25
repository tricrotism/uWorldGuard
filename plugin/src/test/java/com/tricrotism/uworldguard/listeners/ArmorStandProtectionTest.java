package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArmorStandMock;
import org.mockbukkit.mockbukkit.entity.CreeperMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Knocking down an armor stand, by a player or by something with no player behind it such as a
 * creeper. The second case is the one the plain build check missed.
 */
class ArmorStandProtectionTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private EntityListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        listener = new EntityListener(container, container.createQuery());
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

    private boolean hit(final Entity damager, final int x, final int z) {
        final ArmorStand stand = new ArmorStandMock(server, UUID.randomUUID());
        stand.teleport(new Location(world, x, 64, z));
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, stand,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, DamageSource.builder(DamageType.GENERIC).build(), 1.0);
        listener.onEntityDamage(event);
        return !event.isCancelled();
    }

    private CreeperMock creeper() {
        final CreeperMock creeper = new CreeperMock(server, UUID.randomUUID());
        creeper.teleport(new Location(world, 16, 64, 16));
        return creeper;
    }

    @Test
    void aStrangerCannotBreakAStandInAClaim() {
        claim("plot");

        assertFalse(hit(server.addPlayer(), 16, 16));
    }

    @Test
    void anOwnerCanBreakAStandInTheirClaim() {
        final PlayerMock owner = server.addPlayer();
        claim("plot").getOwners().addPlayer(owner.getUniqueId());

        assertTrue(hit(owner, 16, 16));
    }

    @Test
    void anAllowedFlagOpensStandsToEveryone() {
        claim("plot").setFlag(Flags.ENTITY_ARMOR_STAND_DESTROY, State.ALLOW);

        assertTrue(hit(server.addPlayer(), 16, 16));
    }

    @Test
    void aDeniedFlagStopsAnExplosion() {
        claim("plot").setFlag(Flags.ENTITY_ARMOR_STAND_DESTROY, State.DENY);

        assertFalse(hit(creeper(), 16, 16));
    }

    @Test
    void anUnsetFlagLetsAnExplosionThrough() {
        claim("plot");

        assertTrue(hit(creeper(), 16, 16));
    }
}
