package com.tricrotism.uworldguard.blacklist;

import com.tricrotism.uworldguard.listeners.BlacklistListener;
import com.tricrotism.uworldguard.text.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The item blacklist through every listener handler, with the notify, kick and ban actions run
 * through MockBukkit's Folia schedulers.
 */
class BlacklistTest {

    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;
    private BlacklistService service;
    private BlacklistListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(), """
            cooldown-seconds: 0
            messages:
              no-permission: "<red>No."
              blacklist-denied: "<red>Denied <action>."
              blacklist-notify: "<gray><player> tried to <action>."
              blacklist-kick: "<red>Kicked."
              blacklist-ban: "Banned."
            """, StandardCharsets.UTF_8);

        service = new BlacklistService(plugin, new MessageService(plugin));
        listener = new BlacklistListener(service);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void load(final String yaml) {
        service.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)), plugin.getLogger());
    }

    private boolean place(final PlayerMock who, final World in, final Material type) {
        final Block block = in.getBlockAt(5, 64, 5);
        final BlockState before = block.getState();
        block.setType(type);
        final BlockPlaceEvent event = new BlockPlaceEvent(block, before, in.getBlockAt(5, 63, 5),
            new ItemStack(type), who, true, EquipmentSlot.HAND);
        listener.onPlace(event);
        return !event.isCancelled();
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aGlobalEntryRefusesPlacingTntButNotStone() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny]
            """);

        assertFalse(place(player, world, Material.TNT));
        assertTrue(place(player, world, Material.STONE));
    }

    @Test
    void ignorePermsAndIgnoreGroupsLetTheirHoldersPlace() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  ignore-perms: [spawn.builder]
                  ignore-groups: [admin]
                  on-place: [deny]
            """);
        final PlayerMock builder = server.addPlayer();
        builder.addAttachment(plugin, "spawn.builder", true);
        final PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "group.admin", true);

        assertTrue(place(builder, world, Material.TNT));
        assertTrue(place(admin, world, Material.TNT));
        assertFalse(place(player, world, Material.TNT));
    }

    @Test
    void aWorldEntryOnlyAppliesInItsWorldWhileAGlobalEntryAppliesEverywhere() {
        final WorldMock other = server.addSimpleWorld("other");
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny]
            worlds:
              world:
                blacklist:
                  entries:
                    - items: [glass]
                      on-place: [deny]
            """);

        assertFalse(place(player, world, Material.GLASS));
        assertTrue(place(player, other, Material.GLASS));
        assertFalse(place(player, world, Material.TNT));
        assertFalse(place(player, other, Material.TNT));
    }

    @Test
    void whitelistModeRefusesListedItemsUnlessTheRuleSaysAllow() {
        load("""
            blacklist:
              use-as-whitelist: true
              entries:
                - items: [tnt]
                  on-break: [deny]
                - items: [glass]
                  on-place: [allow]
            """);

        assertFalse(place(player, world, Material.TNT), "listed with no place rule");
        assertTrue(place(player, world, Material.GLASS), "listed with on-place allow");
        assertTrue(place(player, world, Material.STONE), "unlisted items are left alone");
    }

    @Test
    void tellSendsTheDeniedMessageOnceWithinTheRepeatWindowButStillRefusesEveryTime() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny, tell]
            """);

        assertFalse(place(player, world, Material.TNT));
        final Component first = player.nextComponentMessage();
        assertNotNull(first);
        assertEquals("Denied place.", plain(first));

        assertFalse(place(player, world, Material.TNT));
        assertNull(player.nextComponentMessage(), "a repeat within three seconds is not told again");
    }

    @Test
    void anEntryMessageReplacesTheDefaultText() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny, tell]
                  message: "<red>No TNT here."
            """);

        assertFalse(place(player, world, Material.TNT));
        final Component told = player.nextComponentMessage();
        assertNotNull(told);
        assertEquals("No TNT here.", plain(told));
    }

    @Test
    void destroyWithRefusesBreakingWhileHoldingTheListedTool() {
        load("""
            blacklist:
              entries:
                - items: [diamond_pickaxe]
                  on-destroy-with: [deny]
            """);
        final Block block = world.getBlockAt(5, 64, 5);
        block.setType(Material.STONE);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));

        final BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void dropDenyRefusesDroppingTheItem() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-drop: [deny]
            """);
        final Item drop = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));

        final PlayerDropItemEvent event = new PlayerDropItemEvent(player, drop);
        listener.onDrop(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void acquireDenyRefusesPickupsSilentlyEvenWithTell() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-acquire: [deny, tell]
            """);
        final Item item = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));

        final EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        listener.onPickup(event);

        assertTrue(event.isCancelled());
        assertNull(player.nextComponentMessage(), "pickups repeat every tick, so nothing is sent");
    }

    @Test
    void logWritesTheAttemptToTheDailyFileOnStop() throws Exception {
        load("""
            blacklist:
              logging:
                console: false
                file: true
              entries:
                - items: [tnt]
                  on-place: [deny, log]
            """);

        assertFalse(place(player, world, Material.TNT));
        service.stop();

        final Path logs = plugin.getDataFolder().toPath().resolve("logs");
        final List<Path> files;
        try (Stream<Path> listing = Files.list(logs)) {
            files = listing.filter(path -> path.getFileName().toString().matches("blacklist-.*\\.log")).toList();
        }
        assertEquals(1, files.size());
        final String text = Files.readString(files.getFirst(), StandardCharsets.UTF_8);
        assertTrue(text.contains(player.getName()), text);
        assertTrue(text.contains("tried to place tnt"), text);
    }

    @Test
    void anEntryNamingOnlyUnknownItemsIsDroppedAndTheRestStillWork() {
        load("""
            blacklist:
              entries:
                - items: [not_a_thing]
                  on-place: [deny]
                - items: [tnt]
                  on-place: [deny]
            """);

        final Blacklist list = service.forWorld(world);
        assertNotNull(list);
        assertEquals(1, list.entries().size());
        assertFalse(place(player, world, Material.TNT));
    }

    @Test
    void nothingValidConfiguredMeansNoList() {
        load("""
            blacklist:
              entries:
                - items: [not_a_thing]
                  on-place: [deny]
            """);

        assertNull(service.forWorld(world));
    }

    @Test
    void dispenseDenyRefusesADispenserWithNoPlayer() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-dispense: [deny]
            """);
        final Block dispenser = world.getBlockAt(5, 64, 5);
        dispenser.setType(Material.DISPENSER);

        final BlockDispenseEvent event = new BlockDispenseEvent(dispenser, new ItemStack(Material.TNT), new Vector());
        listener.onDispense(event);

        assertTrue(event.isCancelled());
    }

    private PlayerInteractEvent interact(
        final org.bukkit.event.block.Action action, final @Nullable ItemStack item, final @Nullable Block clicked
    ) {
        final PlayerInteractEvent event = new PlayerInteractEvent(
            player, action, item, clicked, BlockFace.UP, EquipmentSlot.HAND);
        listener.onInteract(event);
        return event;
    }

    @Test
    void interactDenyRefusesRightClickingTheListedBlock() {
        load("""
            blacklist:
              entries:
                - items: [lever]
                  on-interact: [deny]
            """);
        final Block lever = world.getBlockAt(5, 64, 5);
        lever.setType(Material.LEVER);
        final Block chest = world.getBlockAt(6, 64, 5);
        chest.setType(Material.CHEST);

        assertEquals(Event.Result.DENY,
            interact(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, lever).useInteractedBlock());
        assertEquals(Event.Result.ALLOW,
            interact(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, chest).useInteractedBlock());
    }

    @Test
    void interactDenyCancelsSteppingOnTheListedPlate() {
        load("""
            blacklist:
              entries:
                - items: [stone_pressure_plate]
                  on-interact: [deny]
            """);
        final Block plate = world.getBlockAt(5, 64, 5);
        plate.setType(Material.STONE_PRESSURE_PLATE);

        assertTrue(interact(org.bukkit.event.block.Action.PHYSICAL, null, plate).isCancelled());
    }

    @Test
    void useDenyRefusesTheListedItemWhenClickingAir() {
        load("""
            blacklist:
              entries:
                - items: [ender_pearl]
                  on-use: [deny]
            """);

        assertEquals(Event.Result.DENY, interact(org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
            new ItemStack(Material.ENDER_PEARL), null).useItemInHand());
        assertEquals(Event.Result.DEFAULT, interact(org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
            new ItemStack(Material.SNOWBALL), null).useItemInHand());
    }

    @Test
    void useDenyLeavesBlockItemsToOnPlace() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-use: [deny]
            """);

        assertEquals(Event.Result.DEFAULT, interact(org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
            new ItemStack(Material.TNT), null).useItemInHand());
    }

    @Test
    void equipDenyRefusesPuttingOnArmourByRightClick() {
        load("""
            blacklist:
              entries:
                - items: [diamond_chestplate]
                  on-equip: [deny]
            """);

        assertEquals(Event.Result.DENY, interact(org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
            new ItemStack(Material.DIAMOND_CHESTPLATE), null).useItemInHand());
    }

    @Test
    void useDenyCancelsEatingTheListedFood() {
        load("""
            blacklist:
              entries:
                - items: [golden_apple]
                  on-use: [deny]
            """);

        final PlayerItemConsumeEvent event = new PlayerItemConsumeEvent(
            player, new ItemStack(Material.GOLDEN_APPLE), EquipmentSlot.HAND);
        listener.onConsume(event);

        assertTrue(event.isCancelled());
    }

    private boolean pour(final Material bucket) {
        final Block spot = world.getBlockAt(5, 64, 5);
        final PlayerBucketEmptyEvent event = new PlayerBucketEmptyEvent(player, spot, world.getBlockAt(5, 63, 5),
            BlockFace.UP, bucket, new ItemStack(bucket), EquipmentSlot.HAND);
        listener.onBucketEmpty(event);
        return !event.isCancelled();
    }

    @Test
    void aLavaBucketIsCheckedAsPlacingLava() {
        load("""
            blacklist:
              entries:
                - items: [lava]
                  on-place: [deny]
            """);

        assertFalse(pour(Material.LAVA_BUCKET));
        assertTrue(pour(Material.WATER_BUCKET));
    }

    /**
     * MockBukkit leaves {@code Inventory#getHolder(boolean)} unimplemented, and the listener reads it.
     */
    private static InventoryMock heldBy(final InventoryHolder holder, final int size, final InventoryType type) {
        return new InventoryMock(holder, size, type) {
            @Override
            public InventoryHolder getHolder(final boolean useSnapshot) {
                return getHolder();
            }
        };
    }

    /**
     * The player's own inventory screen: a crafting grid of five raw slots above their inventory, so
     * raw slot 5 is inventory slot 0.
     */
    private PlayerInventoryViewMock ownInventory() {
        return new PlayerInventoryViewMock(player, heldBy(player, 5, InventoryType.CRAFTING));
    }

    @Test
    void acquireDenyRefusesTakingTheItemOutOfAChest() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-acquire: [deny]
            """);
        final InventoryMock chest = heldBy(() -> null, 27, InventoryType.CHEST);
        chest.setItem(0, new ItemStack(Material.DIAMOND));

        final InventoryClickEvent event = new InventoryClickEvent(new PlayerInventoryViewMock(player, chest),
            InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
        assertEquals(Material.DIAMOND, Objects.requireNonNull(chest.getItem(0)).getType(), "the chest keeps it");
    }

    @Test
    void acquireDenyTakesTheItemAwayWhenClickedInTheOwnInventory() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-acquire: [deny]
            """);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));

        final InventoryClickEvent event = new InventoryClickEvent(ownInventory(),
            InventoryType.SlotType.CONTAINER, 5, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
        final ItemStack left = player.getInventory().getItem(0);
        assertTrue(left == null || left.isEmpty(), "the item is taken away");
    }

    @Test
    void equipDenyRefusesPlacingTheChestplateIntoAnArmourSlot() {
        load("""
            blacklist:
              entries:
                - items: [diamond_chestplate]
                  on-equip: [deny]
            """);
        player.setItemOnCursor(new ItemStack(Material.DIAMOND_CHESTPLATE));

        final InventoryClickEvent event = new InventoryClickEvent(ownInventory(),
            InventoryType.SlotType.ARMOR, 6, ClickType.LEFT, InventoryAction.PLACE_ALL);
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void acquireDenyRefusesACreativePickOfTheItem() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-acquire: [deny]
            """);

        final InventoryCreativeEvent event = new InventoryCreativeEvent(ownInventory(),
            InventoryType.SlotType.QUICKBAR, 5, new ItemStack(Material.DIAMOND));
        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void scrollingToAListedItemEmptiesThatSlot() {
        load("""
            blacklist:
              entries:
                - items: [diamond]
                  on-acquire: [deny]
            """);
        player.getInventory().setItem(3, new ItemStack(Material.DIAMOND));
        player.getInventory().setItem(4, new ItemStack(Material.EMERALD));

        listener.onItemHeld(new PlayerItemHeldEvent(player, 0, 3));
        listener.onItemHeld(new PlayerItemHeldEvent(player, 3, 4));

        final ItemStack left = player.getInventory().getItem(3);
        assertTrue(left == null || left.isEmpty());
        assertEquals(Material.EMERALD, Objects.requireNonNull(player.getInventory().getItem(4)).getType());
    }

    @Test
    void equipDenyRefusesArmourDispensedOntoAPlayerSilently() {
        load("""
            blacklist:
              entries:
                - items: [diamond_chestplate]
                  on-equip: [deny, tell]
            """);
        final Block dispenser = world.getBlockAt(5, 64, 5);
        dispenser.setType(Material.DISPENSER);

        final BlockDispenseArmorEvent event = new BlockDispenseArmorEvent(
            dispenser, new ItemStack(Material.DIAMOND_CHESTPLATE), player);
        listener.onDispenseArmor(event);

        assertTrue(event.isCancelled());
        assertNull(player.nextComponentMessage(), "the player did nothing, so nothing is sent");
    }

    @Test
    void notifyReachesStaffWithThePermissionOnly() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny, notify]
            """);
        final PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, BlacklistService.NOTIFY_PERMISSION, true);
        final PlayerMock bystander = server.addPlayer();
        service.useStaff(Set.of(staff.getUniqueId(), bystander.getUniqueId()));
        assertTrue(service.notifies());

        assertFalse(place(player, world, Material.TNT));
        server.getScheduler().performTicks(2);

        final Component line = staff.nextComponentMessage();
        assertNotNull(line);
        assertEquals(player.getName() + " tried to place.", plain(line));
        assertNull(bystander.nextComponentMessage());
        assertNull(player.nextComponentMessage());
    }

    @Test
    void kickRemovesThePlayerOnceTheirSchedulerRuns() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny, kick]
            """);

        assertFalse(place(player, world, Material.TNT));
        assertTrue(player.isOnline(), "the kick waits for the player's own thread");
        server.getScheduler().performTicks(1);

        assertFalse(player.isOnline());
    }

    @Test
    void banBansThePlayerOnceTheirSchedulerRuns() {
        load("""
            blacklist:
              entries:
                - items: [tnt]
                  on-place: [deny, ban]
            """);

        assertFalse(place(player, world, Material.TNT));
        assertFalse(player.isBanned());
        server.getScheduler().performTicks(1);

        assertTrue(player.isBanned());
        assertFalse(player.isOnline());
    }
}
