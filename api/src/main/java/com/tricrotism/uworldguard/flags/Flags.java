package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of all flags. The built-in constants mirror WorldGuard's flag set so stored
 * regions round-trip by name. Not every flag is enforced by a listener yet (see the
 * listeners package); unenforced flags still persist and resolve correctly.
 *
 * <p>Other plugins may contribute their own flags via {@link #register(FlagCategory, Flag)}
 * — registered flags persist, resolve, and appear in the flag menu and command suggestions
 * exactly like built-ins. Register during your plugin's enable, before any region that uses
 * the flag is loaded from storage.
 */
@NullMarked
public final class Flags {

    private static final Map<String, Flag<?>> REGISTRY = new LinkedHashMap<>();

    // Protection.
    public static final StateFlag BUILD = register(FlagCategory.PROTECTION, new StateFlag("build", true));
    public static final StateFlag BLOCK_BREAK = register(FlagCategory.PROTECTION, new StateFlag("block-break", true));
    public static final StateFlag BLOCK_PLACE = register(FlagCategory.PROTECTION, new StateFlag("block-place", true));
    public static final StateFlag INTERACT = register(FlagCategory.PROTECTION, new StateFlag("interact", true));
    public static final StateFlag USE = register(FlagCategory.PROTECTION, new StateFlag("use", true));
    public static final StateFlag CHEST_ACCESS = register(FlagCategory.PROTECTION, new StateFlag("chest-access", true));
    public static final StateFlag PVP = register(FlagCategory.PROTECTION, new StateFlag("pvp", true));
    public static final StateFlag DAMAGE_ANIMALS = register(FlagCategory.PROTECTION, new StateFlag("damage-animals", true));
    public static final StateFlag RIDE = register(FlagCategory.PROTECTION, new StateFlag("ride", true));
    public static final StateFlag SLEEP = register(FlagCategory.PROTECTION, new StateFlag("sleep", true));
    public static final StateFlag TNT = register(FlagCategory.PROTECTION, new StateFlag("tnt", true));
    public static final StateFlag LIGHTER = register(FlagCategory.PROTECTION, new StateFlag("lighter", true));
    public static final StateFlag ENTRY = register(FlagCategory.ENTRY, new StateFlag("entry", true));
    public static final StateFlag EXIT = register(FlagCategory.ENTRY, new StateFlag("exit", true));

    // Mobs & explosions.
    public static final StateFlag MOB_SPAWNING = register(FlagCategory.MOBS, new StateFlag("mob-spawning", true));
    public static final StateFlag MOB_DAMAGE = register(FlagCategory.MOBS, new StateFlag("mob-damage", true));
    public static final StateFlag CREEPER_EXPLOSION = register(FlagCategory.MOBS, new StateFlag("creeper-explosion", true));
    public static final StateFlag OTHER_EXPLOSION = register(FlagCategory.MOBS, new StateFlag("other-explosion", true));
    public static final StateFlag ENDERMAN_GRIEF = register(FlagCategory.MOBS, new StateFlag("enderman-grief", true));
    public static final StateFlag GHAST_FIREBALL = register(FlagCategory.MOBS, new StateFlag("ghast-fireball", true));

    // Natural events.
    public static final StateFlag FIRE_SPREAD = register(FlagCategory.ENVIRONMENT, new StateFlag("fire-spread", true));
    public static final StateFlag LAVA_FIRE = register(FlagCategory.ENVIRONMENT, new StateFlag("lava-fire", true));
    public static final StateFlag LAVA_FLOW = register(FlagCategory.ENVIRONMENT, new StateFlag("lava-flow", true));
    public static final StateFlag WATER_FLOW = register(FlagCategory.ENVIRONMENT, new StateFlag("water-flow", true));
    public static final StateFlag SNOW_FALL = register(FlagCategory.ENVIRONMENT, new StateFlag("snow-fall", true));
    public static final StateFlag SNOW_MELT = register(FlagCategory.ENVIRONMENT, new StateFlag("snow-melt", true));
    public static final StateFlag ICE_FORM = register(FlagCategory.ENVIRONMENT, new StateFlag("ice-form", true));
    public static final StateFlag ICE_MELT = register(FlagCategory.ENVIRONMENT, new StateFlag("ice-melt", true));
    public static final StateFlag LEAF_DECAY = register(FlagCategory.ENVIRONMENT, new StateFlag("leaf-decay", true));
    public static final StateFlag CROP_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("crop-growth", true));
    public static final StateFlag VINE_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("vine-growth", true));

    // Movement & teleport.
    public static final StateFlag ENDERPEARL = register(FlagCategory.MOVEMENT, new StateFlag("enderpearl", true));
    public static final StateFlag CHORUS_TELEPORT = register(FlagCategory.MOVEMENT, new StateFlag("chorus-fruit-teleport", true));

    // Messages & effects.
    public static final StringFlag GREETING = register(FlagCategory.MESSAGES, new StringFlag("greeting"));
    public static final StringFlag FAREWELL = register(FlagCategory.MESSAGES, new StringFlag("farewell"));
    public static final StringFlag ENTRY_DENY_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("entry-deny-message"));
    public static final StringFlag EXIT_DENY_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("exit-deny-message"));
    public static final BooleanFlag INVINCIBLE = register(FlagCategory.PLAYER, new BooleanFlag("invincible"));
    public static final DoubleFlag HEAL_AMOUNT = register(FlagCategory.PLAYER, new DoubleFlag("heal-amount"));
    public static final DoubleFlag HEAL_MIN_HEALTH = register(FlagCategory.PLAYER, new DoubleFlag("heal-min-health"));
    public static final DoubleFlag HEAL_MAX_HEALTH = register(FlagCategory.PLAYER, new DoubleFlag("heal-max-health"));
    public static final StringFlag GAME_MODE = register(FlagCategory.PLAYER, new StringFlag("game-mode"));

    // Item-use control.
    public static final MaterialSetFlag DISABLE_COMPLETELY = register(FlagCategory.ITEMS, new MaterialSetFlag("disable-completely"));
    public static final BooleanFlag DISABLE_THROW = register(FlagCategory.ITEMS, new BooleanFlag("disable-throw"));
    public static final StateFlag VILLAGER_TRADE = register(FlagCategory.ITEMS, new StateFlag("villager-trade", true));
    public static final StateFlag PERMIT_WORKBENCHES = register(FlagCategory.ITEMS, new StateFlag("permit-workbenches", true));
    public static final StateFlag INVENTORY_CRAFT = register(FlagCategory.ITEMS, new StateFlag("inventory-craft", true));
    public static final MaterialSetFlag DENY_ITEM_DROPS = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-item-drops"));
    public static final MaterialSetFlag DENY_ITEM_PICKUP = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-item-pickup"));

    // Fine-grained block control. deny-* blacklists materials (even for members); allow-* permits
    // materials, overriding region build protection.
    public static final MaterialSetFlag ALLOW_BLOCK_PLACE = register(FlagCategory.ITEMS, new MaterialSetFlag("allow-block-place"));
    public static final MaterialSetFlag DENY_BLOCK_PLACE = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-block-place"));
    public static final MaterialSetFlag ALLOW_BLOCK_BREAK = register(FlagCategory.ITEMS, new MaterialSetFlag("allow-block-break"));
    public static final MaterialSetFlag DENY_BLOCK_BREAK = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-block-break"));

    // Entry restrictions (values may be a number or, with PlaceholderAPI, a %placeholder%).
    public static final StringFlag ENTRY_MIN_LEVEL = register(FlagCategory.ENTRY, new StringFlag("entry-min-level"));
    public static final StringFlag ENTRY_MAX_LEVEL = register(FlagCategory.ENTRY, new StringFlag("entry-max-level"));
    public static final IntegerFlag PLAYER_COUNT_LIMIT = register(FlagCategory.ENTRY, new IntegerFlag("player-count-limit"));

    // Enter/leave actions.
    public static final StringFlag TELEPORT_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("teleport-on-entry"));
    public static final StringFlag TELEPORT_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("teleport-on-exit"));
    public static final StringFlag COMMAND_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("command-on-entry"));
    public static final StringFlag COMMAND_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("command-on-exit"));
    public static final StringFlag CONSOLE_COMMAND_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("console-command-on-entry"));
    public static final StringFlag CONSOLE_COMMAND_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("console-command-on-exit"));
    public static final StringFlag PLAY_SOUNDS = register(FlagCategory.MESSAGES, new StringFlag("play-sounds"));
    public static final StringFlag RESPAWN_LOCATION = register(FlagCategory.ENTRY, new StringFlag("respawn-location"));

    // Continuous player state while inside.
    public static final DoubleFlag WALK_SPEED = register(FlagCategory.PLAYER, new DoubleFlag("walk-speed"));
    public static final DoubleFlag FLY_SPEED = register(FlagCategory.PLAYER, new DoubleFlag("fly-speed"));
    public static final BooleanFlag FLY = register(FlagCategory.PLAYER, new BooleanFlag("fly"));
    public static final BooleanFlag GODMODE = register(FlagCategory.PLAYER, new BooleanFlag("godmode"));

    // Death / misc toggles.
    public static final BooleanFlag KEEP_INVENTORY = register(FlagCategory.PLAYER, new BooleanFlag("keep-inventory"));
    public static final BooleanFlag KEEP_EXP = register(FlagCategory.PLAYER, new BooleanFlag("keep-exp"));
    public static final StateFlag GLIDE = register(FlagCategory.MOVEMENT, new StateFlag("glide", true));
    public static final StateFlag NETHER_PORTALS = register(FlagCategory.MOVEMENT, new StateFlag("nether-portals", true));
    public static final StateFlag ITEM_DURABILITY = register(FlagCategory.ITEMS, new StateFlag("item-durability", true));
    public static final BooleanFlag DISABLE_COLLISION = register(FlagCategory.PLAYER, new BooleanFlag("disable-collision"));
    public static final StateFlag CHAMBERED_ENDERPEARL = register(FlagCategory.MOVEMENT, new StateFlag("chambered-enderpearl", true));

    private Flags() {
    }

    /**
     * Register a custom flag under the given menu category.
     *
     * @return the flag, for assignment to a constant
     * @throws IllegalStateException if a flag with the same name is already registered
     */
    public static synchronized <F extends Flag<?>> F register(final FlagCategory category, final F flag) {
        final String key = flag.getName().toLowerCase(Locale.ROOT);
        if (REGISTRY.containsKey(key)) {
            throw new IllegalStateException("A flag named '" + flag.getName() + "' is already registered");
        }
        flag.setCategory(category);
        REGISTRY.put(key, flag);
        return flag;
    }

    public static synchronized @Nullable Flag<?> get(final String name) {
        return REGISTRY.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Snapshot of every registered flag, in registration order.
     */
    public static synchronized List<Flag<?>> all() {
        return List.copyOf(REGISTRY.values());
    }
}
