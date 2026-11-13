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
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PigMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canvas fires only its own async teleport and portal events, never {@code PlayerTeleportEvent} or
 * {@code PlayerPortalEvent}. Stand-ins shaped like Canvas's events drive the bridge.
 */
class CanvasTeleportsTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    /**
     * Shaped like {@code io.canvasmc.canvas.event.EntityTeleportAsyncEvent}.
     */
    public static final class Before extends EntityEvent implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Location from;
        private final Location to;
        private final TeleportCause cause;
        private boolean cancelled;

        public Before(final Entity entity, final Location from, final Location to, final TeleportCause cause) {
            super(entity);
            this.from = from;
            this.to = to;
            this.cause = cause;
        }

        public Location getFrom() {
            return from;
        }

        public Location getTo() {
            return to;
        }

        public TeleportCause getCause() {
            return cause;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(final boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /**
     * Shaped like {@code io.canvasmc.canvas.event.EntityPostTeleportAsyncEvent}.
     */
    public static final class After extends EntityEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Location from;
        private final Location to;
        private final TeleportCause cause;

        public After(final Entity entity, final Location from, final Location to, final TeleportCause cause) {
            super(entity);
            this.from = from;
            this.to = to;
            this.cause = cause;
        }

        public Location getFrom() {
            return from;
        }

        public Location getTo() {
            return to;
        }

        public TeleportCause getCause() {
            return cause;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /**
     * Shaped like {@code io.canvasmc.canvas.event.EntityPortalAsyncEvent}.
     */
    public static final class PortalBefore extends EntityEvent implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final World from;
        private final World to;
        private boolean cancelled;

        public PortalBefore(final Entity entity, final World from, final World to) {
            super(entity);
            this.from = from;
            this.to = to;
        }

        public World getFrom() {
            return from;
        }

        public World getTo() {
            return to;
        }

        public PortalType getPortalType() {
            return PortalType.NETHER;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(final boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /**
     * Shaped like {@code io.canvasmc.canvas.event.EntityPostPortalAsyncEvent}.
     */
    public static final class PortalAfter extends EntityEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        public PortalAfter(final Entity entity) {
            super(entity);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private ServerMock server;
    private WorldMock world;
    private WorldMock nether;
    private PlayerMock player;
    private @Nullable Location sentBackTo;
    private ProtectedCuboidRegion spawn;
    private RegionManager netherRegions;
    private int locationReads;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        nether = server.addSimpleWorld("world_nether");
        nether.setEnvironment(World.Environment.NETHER);
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(world);
        netherRegions = container.get(nether);
        assertNotNull(manager);
        assertNotNull(netherRegions);
        final RegionQuery query = container.createQuery();
        final MessageService messages = new MessageService(plugin);
        final MovementListener movement = new MovementListener(plugin, query, messages, new CollisionService(plugin),
            new ChamberedPearlTracker(plugin), new ChatTags(), new PendingRestores(plugin),
            new PlayerTickService(plugin, container, query, new ChunkUnloadService(plugin, container)),
            new Settings());
        assertTrue(CanvasTeleports.register(plugin, new PlayerStateListener(query, messages), movement,
            new TravelListener(query), Before.class.getName(), After.class.getName(),
            PortalBefore.class.getName(), PortalAfter.class.getName()));
        player = new PlayerMock(server, "Traveller") {
            // MockBukkit does not implement it, and the bridge sends refused arrivals back with it
            @Override
            public CompletableFuture<Boolean> teleportAsync(final Location location, final TeleportCause cause,
                                                            final TeleportFlag... flags) {
                sentBackTo = location;
                return CompletableFuture.completedFuture(true);
            }
        };
        server.addPlayer(player);

        spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(-100, 0, -100), BlockVector3.at(100, 255, 100));
        spawn.setFlag(Flags.ENDERPEARL, State.DENY);
        manager.addRegion(spawn);
        final ProtectedCuboidRegion vault = new ProtectedCuboidRegion("vault",
            BlockVector3.at(300, 0, 0), BlockVector3.at(320, 255, 20));
        vault.setFlag(Flags.ENTRY, State.DENY);
        manager.addRegion(vault);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private boolean refused(final int fromX, final int toX, final TeleportCause cause) {
        final Before event = new Before(player, new Location(world, fromX + 0.5, 64, 5.5),
            new Location(world, toX + 0.5, 64, 5.5), cause);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean portalRefused(final int x) {
        player.setLocation(new Location(world, x + 0.5, 64, 5.5));
        final PortalBefore event = new PortalBefore(player, world, nether);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void aPearlOutOfARegionDenyingPearlsIsRefused() {
        assertTrue(refused(5, 200, TeleportCause.ENDER_PEARL));
    }

    @Test
    void aPearlWhereNoRegionDeniesPearlsGoesThrough() {
        assertFalse(refused(200, 210, TeleportCause.ENDER_PEARL));
    }

    @Test
    void aTeleportIntoARegionDenyingEntryIsRefused() {
        assertTrue(refused(200, 310, TeleportCause.PLUGIN));
    }

    @Test
    void aPortalInARegionDenyingPortalsIsRefused() {
        spawn.setFlag(Flags.NETHER_PORTALS, State.DENY);

        assertTrue(portalRefused(5));
        assertFalse(portalRefused(200));
    }

    @Test
    void aPortalInARegionDenyingPortalsRefusesAPlayersMount() {
        spawn.setFlag(Flags.NETHER_PORTALS, State.DENY);
        final Location at = new Location(world, 5.5, 64, 5.5);
        player.setLocation(at);
        final Pig pig = world.spawn(at, Pig.class);
        assertTrue(pig.addPassenger(player));
        final PortalBefore event = new PortalBefore(pig, world, nether);

        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aRiderlessMobThroughAPortalIsNotLocated() {
        final PigMock pig = new PigMock(server, UUID.randomUUID()) {
            @Override
            public Location getLocation() {
                locationReads++;
                return super.getLocation();
            }
        };
        pig.setLocation(new Location(world, 5.5, 64, 5.5));
        locationReads = 0;
        final PortalBefore event = new PortalBefore(pig, world, nether);

        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled());
        assertEquals(0, locationReads, "nothing can refuse a riderless mob, so its position is never read");
    }

    @Test
    void aPortalOutOfARegionThatHoldsItsPlayersIsRefused() {
        spawn.setFlag(Flags.EXIT, State.DENY);
        spawn.setFlag(Flags.EXIT_VIA_TELEPORT, State.DENY);

        assertTrue(portalRefused(5));
    }

    @Test
    void aPlayerWhoLandsWhereEntryIsDeniedIsSentBack() {
        final ProtectedCuboidRegion keep = new ProtectedCuboidRegion("keep",
            BlockVector3.at(0, 0, 0), BlockVector3.at(50, 255, 50));
        keep.setFlag(Flags.ENTRY, State.DENY);
        netherRegions.addRegion(keep);
        assertFalse(portalRefused(200));

        player.setLocation(new Location(nether, 25.5, 64, 25.5));
        server.getPluginManager().callEvent(new PortalAfter(player));

        assertNotNull(sentBackTo, "landing where entry is denied sends the player back");
        assertEquals(world, sentBackTo.getWorld());
        assertEquals(200, sentBackTo.getBlockX());
    }

    @Test
    void aPlayerWhoLandsWhereEntryIsAllowedStays() {
        assertFalse(portalRefused(200));

        player.setLocation(new Location(nether, 25.5, 64, 25.5));
        server.getPluginManager().callEvent(new PortalAfter(player));

        assertNull(sentBackTo);
    }
}
