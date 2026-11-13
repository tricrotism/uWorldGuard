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
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flag cases from a support ticket and its follow-ups: pvp across a region border, wind charges and
 * rods reaching into deny regions, {@code use} and {@code allow-interact} opening blocks for
 * strangers, and players climbing on blocks the region refused.
 */
class TicketFlagTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionContainerImpl container;
    private BuildProtectionListener build;
    private ItemUseListener items;
    private WorkbenchListener workbenches;
    private PlayerStateListener containers;
    private @Nullable Location teleportedTo;

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
        final MessageService messages = new MessageService(plugin);
        final RegionQuery query = container.createQuery();
        build = new BuildProtectionListener(query, messages, true);
        items = new ItemUseListener(container, query, messages);
        workbenches = new WorkbenchListener(container, query, messages);
        containers = new PlayerStateListener(query, messages);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ProtectedCuboidRegion claim(final String id, final int minX, final int maxX) {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(minX, 0, 0), BlockVector3.at(maxX, 255, 32));
        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        manager.addRegion(region);
        return region;
    }

    private PlayerMock playerAt(final int x) {
        final PlayerMock player = server.addPlayer();
        player.setLocation(new Location(world, x + 0.5, 64, 16.5));
        return player;
    }

    @SuppressWarnings("deprecation")
    private boolean hit(final PlayerMock attacker, final PlayerMock defender) {
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
            attacker, defender, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
        build.onPvp(event);
        return !event.isCancelled();
    }

    @Test
    void anAttackerInAPvpDenyRegionCannotHitIntoAPvpAllowRegion() {
        claim("safe", 0, 32).setFlag(Flags.PVP, State.DENY);
        claim("arena", 33, 64).setFlag(Flags.PVP, State.ALLOW);
        final PlayerMock attacker = playerAt(32);
        final PlayerMock defender = playerAt(34);

        assertFalse(hit(attacker, defender));
    }

    @Test
    void anAttackerInAPvpDenyRegionCannotSetFireIntoAPvpAllowRegion() {
        claim("safe", 0, 32).setFlag(Flags.PVP, State.DENY);
        claim("arena", 33, 64).setFlag(Flags.PVP, State.ALLOW);
        final PlayerMock attacker = playerAt(32);
        final PlayerMock defender = playerAt(34);

        final EntityCombustByEntityEvent event = new EntityCombustByEntityEvent(attacker, defender, 4.0f);
        new EntityListener(container, container.createQuery()).onCombustByEntity(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aDefenderInAPvpDenyRegionStillCannotBeHit() {
        claim("safe", 0, 32).setFlag(Flags.PVP, State.DENY);
        final PlayerMock attacker = playerAt(40);
        final PlayerMock defender = playerAt(16);

        assertFalse(hit(attacker, defender));
    }

    private static WindCharge chargeFrom(final PlayerMock shooter) {
        return (WindCharge) Proxy.newProxyInstance(TicketFlagTest.class.getClassLoader(),
            new Class<?>[]{WindCharge.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getShooter" -> shooter;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /**
     * A burst as Paper reports it: an explosion pushed by whoever threw the charge, not the charge.
     */
    private static EntityPushedByEntityAttackEvent burst(final PlayerMock victim, final PlayerMock thrower) {
        return new EntityPushedByEntityAttackEvent(
            victim, EntityKnockbackEvent.Cause.EXPLOSION, thrower, new Vector(0, 1, 0));
    }

    @Test
    void aWindChargeDenyRegionStopsAPlayerLaunchingThemselves() {
        claim("spawn", 0, 32).setFlag(Flags.WIND_CHARGE, State.DENY);
        final PlayerMock player = playerAt(16);

        final EntityPushedByEntityAttackEvent event = burst(player, player);
        items.onWindChargeKnockback(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aWindChargeFromOutsideCannotLaunchAPlayerInADenyRegion() {
        claim("spawn", 0, 32).setFlag(Flags.WIND_CHARGE, State.DENY);

        final EntityPushedByEntityAttackEvent event = burst(playerAt(16), playerAt(40));
        items.onWindChargeKnockback(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aWindChargeFromOutsideCannotLaunchAPlayerInAPvpDenyRegion() {
        claim("spawn", 0, 32).setFlag(Flags.PVP, State.DENY);

        final EntityPushedByEntityAttackEvent event = burst(playerAt(16), playerAt(40));
        new EntityListener(container, container.createQuery()).onPvpKnockback(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aWindChargeStillLaunchesPlayersWherePvpIsAllowed() {
        claim("spawn", 0, 32).setFlag(Flags.PVP, State.DENY);

        final EntityPushedByEntityAttackEvent event = burst(playerAt(41), playerAt(40));
        new EntityListener(container, container.createQuery()).onPvpKnockback(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void aFishingRodCannotReelAPlayerOutOfAPvpDenyRegion() {
        claim("spawn", 0, 32).setFlag(Flags.PVP, State.DENY);
        final FishHook hook = (FishHook) Proxy.newProxyInstance(TicketFlagTest.class.getClassLoader(),
            new Class<?>[]{FishHook.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

        final PlayerFishEvent event = new PlayerFishEvent(
            playerAt(40), playerAt(16), hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY);
        new EntityListener(container, container.createQuery()).onFishPlayer(event);

        assertTrue(event.isCancelled());
    }

    private PlayerMock climberAt(final double x, final double y) {
        final PlayerMock player = new PlayerMock(server, "climber") {
            @Override
            public CompletableFuture<Boolean> teleportAsync(
                final Location location, final PlayerTeleportEvent.TeleportCause cause, final TeleportFlag... flags
            ) {
                teleportedTo = location;
                return CompletableFuture.completedFuture(true);
            }
        };
        server.addPlayer(player);
        player.setLocation(new Location(world, x, y, 16.5));
        return player;
    }

    private void placeUnder(final PlayerMock player, final int y) {
        final Block block = world.getBlockAt(16, y, 16);
        final BlockState before = block.getState();
        block.setType(Material.DIRT);
        final BlockPlaceEvent event = new BlockPlaceEvent(block, before, world.getBlockAt(16, y - 1, 16),
            new ItemStack(Material.DIRT), player, true, EquipmentSlot.HAND);
        build.onPlace(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void aRefusedBlockUnderAJumpingPlayerDropsThemBackDown() {
        claim("spawn", 0, 32);
        final PlayerMock climber = climberAt(16.5, 65.2);

        placeUnder(climber, 64);

        assertTrue(climber.getVelocity().getY() < 0, "standing on the refused block would let them climb on air");
        assertNull(teleportedTo, "a teleport per refused block left glitchers always freshly teleported");
    }

    @Test
    void aRefusedBlockBesideTheEdgeAPlayerStandsOnLeavesThemThere() {
        claim("spawn", 0, 32);
        world.getBlockAt(17, 64, 16).setType(Material.STONE);
        final PlayerMock bridger = climberAt(16.9, 65.0);

        placeUnder(bridger, 64);

        assertNull(teleportedTo);
        assertEquals(0.0, bridger.getVelocity().getY());
    }

    @Test
    void aWindChargeDenyRegionStopsTheThrow() {
        claim("spawn", 0, 32).setFlag(Flags.WIND_CHARGE, State.DENY);
        final PlayerMock player = playerAt(16);

        final ProjectileLaunchEvent event = new ProjectileLaunchEvent(chargeFrom(player));
        items.onWindChargeThrow(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aWindChargeThrownOutsideADenyRegionIsLeftAlone() {
        claim("spawn", 0, 32).setFlag(Flags.WIND_CHARGE, State.DENY);
        final PlayerMock player = playerAt(40);

        final ProjectileLaunchEvent event = new ProjectileLaunchEvent(chargeFrom(player));
        items.onWindChargeThrow(event);

        assertFalse(event.isCancelled());
    }

    private boolean open(final PlayerMock player, final Block block) {
        final PlayerInteractEvent event = new PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        build.onInteract(event);
        if (!event.isCancelled()) {
            workbenches.onOpen(event);
        }
        if (!event.isCancelled()) {
            containers.onChestAccess(event);
        }
        return !event.isCancelled();
    }

    private Block blockAt(final Material type) {
        final Block block = world.getBlockAt(16, 64, 17);
        block.setType(type);
        return block;
    }

    @Test
    void allowInteractOpensAListedFurnaceForAStranger() {
        claim("spawn", 0, 32).setFlag(Flags.ALLOW_INTERACT, Set.of(Material.FURNACE, Material.SMITHING_TABLE));

        assertTrue(open(playerAt(16), blockAt(Material.FURNACE)));
    }

    @Test
    void allowInteractOpensAListedSmithingTableForAStranger() {
        claim("spawn", 0, 32).setFlag(Flags.ALLOW_INTERACT, Set.of(Material.FURNACE, Material.SMITHING_TABLE));

        assertTrue(open(playerAt(16), blockAt(Material.SMITHING_TABLE)));
    }

    @Test
    void allowInteractLeavesUnlistedChestsShut() {
        claim("spawn", 0, 32).setFlag(Flags.ALLOW_INTERACT, Set.of(Material.FURNACE));

        assertFalse(open(playerAt(16), blockAt(Material.CHEST)));
    }

    @Test
    void allowInteractOpensAListedFurnaceEvenWhereInteractIsDenied() {
        final ProtectedCuboidRegion region = claim("spawn", 0, 32);
        region.setFlag(Flags.INTERACT, State.DENY);
        region.setFlag(Flags.ALLOW_INTERACT, Set.of(Material.FURNACE));

        assertTrue(open(playerAt(16), blockAt(Material.FURNACE)));
    }

    @Test
    void useAllowOpensASmithingTableForAStranger() {
        claim("spawn", 0, 32).setFlag(Flags.USE, State.ALLOW);
        final PlayerMock stranger = playerAt(16);
        final Block table = world.getBlockAt(16, 64, 17);
        table.setType(Material.SMITHING_TABLE);

        assertTrue(open(stranger, table));
    }

    @Test
    void aStrangerStillCannotOpenASmithingTableInAPlainClaim() {
        claim("plot", 0, 32);
        final PlayerMock stranger = playerAt(16);
        final Block table = world.getBlockAt(16, 64, 17);
        table.setType(Material.SMITHING_TABLE);

        assertFalse(open(stranger, table));
    }
}
