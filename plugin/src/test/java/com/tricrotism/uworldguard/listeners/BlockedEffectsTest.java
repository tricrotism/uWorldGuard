package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent.Action;
import org.bukkit.event.entity.EntityPotionEffectEvent.Cause;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code blocked-effects} refusing an effect as it is applied, so a wither rose cannot land damage
 * in the gap before the once-a-second strip.
 */
class BlockedEffectsTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private PlayerStateListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        final Plugin plugin = MockBukkit.createMockPlugin("uWorldGuard");
        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        listener = new PlayerStateListener(container.createQuery(), new MessageService(plugin));
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion("garden",
            BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        region.setFlag(Flags.BLOCKED_EFFECTS, Set.of(new PotionEffect(PotionEffectType.WITHER, 1, 0)));
        manager.addRegion(region);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock playerAt(final int x, final int z) {
        final PlayerMock player = server.addPlayer();
        player.setLocation(new Location(world, x + 0.5, 64, z + 0.5));
        return player;
    }

    private boolean applied(final LivingEntity entity, final PotionEffectType type) {
        final EntityPotionEffectEvent event = new EntityPotionEffectEvent(entity, null,
            new PotionEffect(type, 40, 0), Cause.WITHER_ROSE, Action.ADDED, false);
        listener.onEffect(event);
        return !event.isCancelled();
    }

    @Test
    void aWitherRoseCannotWitherAPlayerInTheRegion() {
        assertFalse(applied(playerAt(16, 16), PotionEffectType.WITHER));
    }

    @Test
    void anEffectTheRegionDoesNotBlockStillApplies() {
        assertTrue(applied(playerAt(16, 16), PotionEffectType.SPEED));
    }

    @Test
    void aWitherRoseStillWithersAPlayerOutsideTheRegion() {
        assertTrue(applied(playerAt(500, 500), PotionEffectType.WITHER));
    }

    @Test
    void removingABlockedEffectIsNeverRefused() {
        final PlayerMock player = playerAt(16, 16);
        final EntityPotionEffectEvent event = new EntityPotionEffectEvent(player,
            new PotionEffect(PotionEffectType.WITHER, 40, 0), null, Cause.PLUGIN, Action.REMOVED, false);
        listener.onEffect(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void mobsAreLeftToTheirOwnRoses() {
        final ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 16.5, 64, 16.5));
        assertTrue(applied(zombie, PotionEffectType.WITHER));
    }
}
