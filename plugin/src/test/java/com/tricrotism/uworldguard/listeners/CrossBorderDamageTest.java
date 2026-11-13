package com.tricrotism.uworldguard.listeners;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.*;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harm that starts in one region and lands in its neighbour: an arena at x 0..15 next to a safe
 * region at x 16..31. Each case is one WorldGuard 7.0.19 stops.
 */
class CrossBorderDamageTest {

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
    private PluginMock plugin;
    private WorldMock world;
    private RegionQuery query;
    private EntityListener entities;
    private BuildProtectionListener build;
    private PlayerStateListener players;
    private VehicleListener vehicles;
    private ProtectedCuboidRegion arena;
    private ProtectedCuboidRegion safe;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "messages:\n  no-permission: \"<red>You cannot do that here.\"\n", StandardCharsets.UTF_8);

        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        query = container.createQuery();
        final MessageService messages = new MessageService(plugin);
        entities = new EntityListener(container, query);
        build = new BuildProtectionListener(query, messages, true);
        players = new PlayerStateListener(query, messages);
        vehicles = new VehicleListener(query, messages);

        final RegionManager manager = container.get(world);
        assertNotNull(manager);
        arena = new ProtectedCuboidRegion("arena", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 31));
        safe = new ProtectedCuboidRegion("safe", BlockVector3.at(16, 0, 0), BlockVector3.at(31, 255, 31));
        arena.setFlag(Flags.PVP, State.ALLOW);
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

    private <T extends Entity> T place(final T entity, final double x) {
        entity.teleport(at(x));
        return entity;
    }

    private PlayerMock playerAt(final double x) {
        final PlayerMock player = server.addPlayer();
        player.setLocation(at(x));
        return player;
    }

    private static DamageSource source(final DamageType type) {
        return DamageSource.builder(type).build();
    }

    @SuppressWarnings("removal")
    private EntityDamageByEntityEvent damage(final Entity damager, final Entity victim, final EntityDamageEvent.DamageCause cause) {
        return new EntityDamageByEntityEvent(damager, victim, cause, source(DamageType.GENERIC), 4.0);
    }

    @SuppressWarnings("removal")
    private boolean bedBlast(final double victimX) {
        final EntityDamageByBlockEvent event = new EntityDamageByBlockEvent(world.getBlockAt(at(ARENA)), null,
            playerAt(victimX), EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            source(DamageType.BAD_RESPAWN_POINT), 6.0);
        entities.onSourcelessDamage(event);
        return !event.isCancelled();
    }

    @Test
    void aRespawnAnchorCannotHurtAPlayerAcrossTheBorder() {
        safe.setFlag(Flags.OTHER_EXPLOSION, State.DENY);

        assertFalse(bedBlast(SAFE));
        assertTrue(bedBlast(ARENA));
    }

    private TNTPrimedMock tntLitBy(final PlayerMock player) {
        final TNTPrimedMock tnt = new TNTPrimedMock(server, UUID.randomUUID()) {};
        tnt.setSource(player);
        return place(tnt, ARENA);
    }

    @Test
    void tntLitInThePvpArenaCannotHurtAPlayerInTheSafeRegion() {
        safe.setFlag(Flags.PVP, State.DENY);
        final EntityDamageByEntityEvent event =
            damage(tntLitBy(playerAt(ARENA)), playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);

        build.onPvp(event);

        assertTrue(event.isCancelled());
    }

    public static final class PvpOverride implements Listener {
        @EventHandler
        public void onDisallowed(final DisallowedPVPEvent event) {
            event.setCancelled(true);
        }
    }

    @Test
    void aPluginCancellingDisallowedPvpLetsTheHitThrough() {
        safe.setFlag(Flags.PVP, State.DENY);
        server.getPluginManager().registerEvents(new PvpOverride(), plugin);
        final EntityDamageByEntityEvent event =
            damage(playerAt(SAFE), playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        build.onPvp(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void yourOwnTntIsNotPvp() {
        safe.setFlag(Flags.PVP, State.DENY);
        final PlayerMock player = playerAt(SAFE);
        final EntityDamageByEntityEvent event =
            damage(tntLitBy(player), player, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);

        build.onPvp(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void anEndermitesBiteRespectsMobDamage() {
        safe.setFlag(Flags.MOB_DAMAGE, State.DENY);
        final EntityDamageByEntityEvent event = damage(place(new EndermiteMock(server, UUID.randomUUID()), SAFE),
            playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        entities.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aPearlsEndermiteRespectsMobSpawningAndASpawnEggStillWorks() {
        safe.setFlag(Flags.MOB_SPAWNING, State.DENY);
        final CreatureSpawnEvent pearl = new CreatureSpawnEvent(
            place(new EndermiteMock(server, UUID.randomUUID()), SAFE), CreatureSpawnEvent.SpawnReason.ENDER_PEARL);
        final CreatureSpawnEvent egg = new CreatureSpawnEvent(
            place(new EndermiteMock(server, UUID.randomUUID()), SAFE), CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        entities.onSpawn(pearl);
        entities.onSpawn(egg);

        assertTrue(pearl.isCancelled(), "a pearl landing in the region does not bring an endermite with it");
        assertFalse(egg.isCancelled(), "mob-spawning governs mobs the world spawns, not ones a player places");
    }

    @Test
    void aSkeletonsArrowRespectsMobDamage() {
        safe.setFlag(Flags.MOB_DAMAGE, State.DENY);
        final ArrowMock arrow = place(new ArrowMock(server, UUID.randomUUID()), ARENA);
        arrow.setShooter(place(new SkeletonMock(server, UUID.randomUUID()), ARENA));
        final EntityDamageByEntityEvent event = damage(arrow, playerAt(SAFE), EntityDamageEvent.DamageCause.PROJECTILE);

        entities.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aMobsFireballRespectsGhastFireball() {
        safe.setFlag(Flags.GHAST_FIREBALL, State.DENY);
        final LargeFireballMock fireball = place(new LargeFireballMock(server, UUID.randomUUID()), ARENA);
        fireball.setShooter(place(new SkeletonMock(server, UUID.randomUUID()), ARENA));
        final EntityDamageByEntityEvent event =
            damage(fireball, playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);

        entities.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aCreeperBlastRespectsCreeperExplosionAtTheVictim() {
        safe.setFlag(Flags.CREEPER_EXPLOSION, State.DENY);
        final EntityDamageByEntityEvent event = damage(place(new CreeperMock(server, UUID.randomUUID()), ARENA),
            playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);

        entities.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    @SuppressWarnings("removal")
    void witherTicksRespectMobDamage() {
        safe.setFlag(Flags.MOB_DAMAGE, State.DENY);
        final EntityDamageEvent event = new EntityDamageEvent(playerAt(SAFE),
            EntityDamageEvent.DamageCause.WITHER, source(DamageType.WITHER), 1.0);

        entities.onSourcelessDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aWitchsPotionRespectsMobDamage() {
        safe.setFlag(Flags.MOB_DAMAGE, State.DENY);
        final ThrownPotionMock potion = place(new ThrownPotionMock(server, UUID.randomUUID()) {
            @Override
            public EntityType getType() {
                return EntityType.SPLASH_POTION;
            }
        }, ARENA);
        potion.setShooter(place(new WitchMock(server, UUID.randomUUID()), ARENA));
        final PlayerMock victim = playerAt(SAFE);
        final Map<LivingEntity, Double> affected = new HashMap<>();
        affected.put(victim, 1.0);
        final PotionSplashEvent event = new PotionSplashEvent(potion, null, null, null, affected);

        entities.onPotionSplash(event);

        assertEquals(0.0, event.getIntensity(victim));
    }

    @Test
    void channelingLightningIsThePlayersPvp() {
        safe.setFlag(Flags.PVP, State.DENY);
        final LightningStrikeMock bolt = place(new LightningStrikeMock(server, UUID.randomUUID()), SAFE);
        bolt.setCausingPlayer(playerAt(ARENA));
        final EntityDamageByEntityEvent event = damage(bolt, playerAt(SAFE), EntityDamageEvent.DamageCause.LIGHTNING);

        build.onPvp(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void theLightningFlagIsJudgedWhereTheVictimStands() {
        safe.setFlag(Flags.LIGHTNING, State.DENY);
        final EntityDamageByEntityEvent event = damage(place(new LightningStrikeMock(server, UUID.randomUUID()), ARENA),
            playerAt(SAFE), EntityDamageEvent.DamageCause.LIGHTNING);

        entities.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    private WolfMock wolfOf(final PlayerMock owner) {
        final WolfMock wolf = place(new WolfMock(server, UUID.randomUUID()), SAFE);
        wolf.setOwner(owner);
        return wolf;
    }

    @Test
    void aTamedWolfIsItsOwnersPvp() {
        safe.setFlag(Flags.PVP, State.DENY);
        final EntityDamageByEntityEvent event =
            damage(wolfOf(playerAt(ARENA)), playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        build.onPvp(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aTamedWolfIsNotMobDamage() {
        safe.setFlag(Flags.PVP, State.ALLOW);
        safe.setFlag(Flags.MOB_DAMAGE, State.DENY);
        final EntityDamageByEntityEvent event =
            damage(wolfOf(playerAt(ARENA)), playerAt(SAFE), EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        entities.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    private boolean frameSurvivesBedBlast(final double x) {
        final HangingBreakEvent event = new HangingBreakEvent(
            place(new ItemFrameMock(server, UUID.randomUUID()), x), HangingBreakEvent.RemoveCause.EXPLOSION);
        entities.onHangingExplode(event);
        return event.isCancelled();
    }

    @Test
    void anExplosionWithNoEntityCannotDropAFrameInAClaim() {
        assertTrue(frameSurvivesBedBlast(SAFE));
        assertFalse(frameSurvivesBedBlast(WILD));
    }

    private boolean cowSurvivesCreeperAt(final double creeperX) {
        final EntityDamageByEntityEvent event = damage(place(new CreeperMock(server, UUID.randomUUID()), creeperX),
            place(new CowMock(server, UUID.randomUUID()), SAFE), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        entities.onEntityDamage(event);
        return event.isCancelled();
    }

    @Test
    void aCreeperOutsideAFarmCannotKillItsCows() {
        assertTrue(cowSurvivesCreeperAt(ARENA));
        assertFalse(cowSurvivesCreeperAt(SAFE));
    }

    @Test
    void lightningOutsideAFarmCannotSetItsCowsAlight() {
        final EntityCombustByEntityEvent event = new EntityCombustByEntityEvent(
            place(new LightningStrikeMock(server, UUID.randomUUID()), ARENA),
            place(new CowMock(server, UUID.randomUUID()), SAFE), 8.0f);

        entities.onCombustByEntity(event);

        assertTrue(event.isCancelled());
    }

    private boolean cowSurvivesPigAt(final double pigX) {
        final EntityDamageByEntityEvent event = damage(place(new PigMock(server, UUID.randomUUID()), pigX),
            place(new CowMock(server, UUID.randomUUID()), SAFE), EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        entities.onEntityDamage(event);
        return event.isCancelled();
    }

    /**
     * A pig is a vehicle too, but a mob counts from where it stands, not from where it spawned.
     */
    @Test
    void aRideableMobIsJudgedWhereItStands() {
        assertTrue(cowSurvivesPigAt(ARENA));
        assertFalse(cowSurvivesPigAt(SAFE));
    }

    @Test
    void aCreeperOutsideAClaimCannotBreakItsCarts() {
        final VehicleDestroyEvent event = new VehicleDestroyEvent(
            place(new RideableMinecartMock(server, UUID.randomUUID()), SAFE),
            place(new CreeperMock(server, UUID.randomUUID()), ARENA));

        vehicles.onDestroy(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aStrangerCannotReelACowOutOfAClaim() {
        final FishHook hook = (FishHook) Proxy.newProxyInstance(CrossBorderDamageTest.class.getClassLoader(),
            new Class<?>[]{FishHook.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
        final PlayerFishEvent event = new PlayerFishEvent(playerAt(ARENA),
            place(new CowMock(server, UUID.randomUUID()), SAFE), hook, EquipmentSlot.HAND,
            PlayerFishEvent.State.CAUGHT_ENTITY);

        entities.onFishPlayer(event);

        assertTrue(event.isCancelled());
    }

    private List<Block> blocksAt(final int... xs) {
        final List<Block> blocks = new ArrayList<>();
        for (final int x : xs) {
            blocks.add(world.getBlockAt(x, 63, 8));
        }
        return blocks;
    }

    private static List<Integer> xs(final List<Block> blocks) {
        return blocks.stream().map(Block::getX).toList();
    }

    private List<Integer> afterBlast(final Entity source, final int... xs) {
        final List<Block> blocks = blocksAt(xs);
        entities.onExplode(new EntityExplodeEvent(source, source.getLocation(), blocks, 1.0F, ExplosionResult.DESTROY));
        return xs(blocks);
    }

    private TNTPrimedMock tntPrimedAt(final double x) {
        final Location origin = at(x);
        return new TNTPrimedMock(server, UUID.randomUUID()) {
            @Override
            public Location getOrigin() {
                return origin;
            }
        };
    }

    @Test
    void aCreeperOutsideAClaimCannotBlowAHoleInIt() {
        assertEquals(List.of(14, 40), afterBlast(place(new CreeperMock(server, UUID.randomUUID()), ARENA), 14, 17, 40));
    }

    @Test
    void tntIsJudgedWherePrimedNotWhereItLands() {
        final TNTPrimedMock fromOutside = place(tntPrimedAt(WILD), SAFE);
        assertEquals(List.of(40), afterBlast(fromOutside, 17, 40));

        final TNTPrimedMock fromInside = place(tntPrimedAt(SAFE), SAFE);
        assertEquals(List.of(17), afterBlast(fromInside, 14, 17));
    }

    @Test
    void aPlayersTntBreaksOnlyWhereTheyMayBuild() {
        final PlayerMock member = playerAt(SAFE);
        safe.getMembers().addPlayer(member.getUniqueId());

        assertEquals(List.of(17, 40), afterBlast(tntLitBy(member), 14, 17, 40));
    }

    @Test
    void tntAllowOpensAClaimToAnyonesTnt() {
        safe.setFlag(Flags.TNT, State.ALLOW);

        assertEquals(List.of(17), afterBlast(place(tntPrimedAt(WILD), SAFE), 17));
    }

    @Test
    void aRespawnAnchorBlastStaysInItsClaim() {
        final Block anchor = world.getBlockAt(14, 63, 8);
        final List<Block> blocks = blocksAt(14, 17, 40);
        new NaturalListener(query).onBlockExplode(
            new BlockExplodeEvent(anchor, anchor.getState(), blocks, 1.0F, ExplosionResult.DESTROY));

        assertEquals(List.of(14, 40), xs(blocks));
    }

    @Test
    @SuppressWarnings("removal")
    void anElytraCrashIsFallDamage() {
        safe.setFlag(Flags.FALL_DAMAGE, State.DENY);
        final EntityDamageEvent event = new EntityDamageEvent(playerAt(SAFE),
            EntityDamageEvent.DamageCause.FLY_INTO_WALL, source(DamageType.FLY_INTO_WALL), 4.0);

        players.onDamage(event);

        assertTrue(event.isCancelled());
    }
}
