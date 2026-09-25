package com.tricrotism.uworldguard.flags;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry of all flags. The built-in constants mirror WorldGuard's flag set so stored
 * regions round-trip by name. Not every flag is enforced by a listener yet (see the
 * listeners package); unenforced flags still persist and resolve correctly.
 *
 * <p>Other plugins may contribute their own flags via {@link #register(FlagCategory, Flag)}
 * — registered flags persist, resolve, and appear in the flag menu and command suggestions
 * exactly like built-ins. Registering after regions have loaded is fine: stored values for a name
 * nothing has registered yet are kept as written, and applied the moment the flag registers.
 *
 * <p>A flag belongs to the plugin whose code registered it. When that plugin disables, its flags
 * are released and their values go back to being kept as written, so the plugin can be reloaded
 * in place (with a hot-swap tool such as Cork) and register the same names again.
 */
@NullMarked
public final class Flags {

    private static final Map<String, Flag<?>> BY_NAME = new ConcurrentHashMap<>();
    private static volatile List<Flag<?>> ordered = List.of();
    /**
     * Next never-used index. Separate from {@code ordered.size()} because a released flag leaves
     * the list but keeps its index reserved, and bitsets sized off the list would then be too short
     * for every flag registered after it.
     */
    private static volatile int nextIndex;
    /**
     * Indexes of released flags, by name, handed back when that name registers again, so a plugin
     * reloaded any number of times reuses one slot instead of growing every region bitset.
     */
    private static final Map<String, Integer> RELEASED = new ConcurrentHashMap<>();
    /**
     * The classloader of the plugin that registered each flag. Strong references, dropped by
     * {@link #release}, which runs while that plugin disables and before its loader closes.
     */
    private static final Map<Flag<?>, ClassLoader> OWNERS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static volatile Consumer<Flag<?>> onRegister = _ -> {};
    /**
     * False while the built-in constants below register. Whatever first touches this class runs
     * that initialization on its own stack, and when that is another plugin the stack walk would
     * credit it with every built-in flag.
     */
    private static volatile boolean builtInsRegistered;

    // Protection.
    public static final StateFlag BUILD = register(FlagCategory.PROTECTION, new StateFlag("build", true));
    public static final StateFlag BLOCK_BREAK = register(FlagCategory.PROTECTION, new StateFlag("block-break", true));
    public static final StateFlag BLOCK_PLACE = register(FlagCategory.PROTECTION, new StateFlag("block-place", true));
    public static final StateFlag INTERACT = register(FlagCategory.PROTECTION, new StateFlag("interact", true));
    public static final StateFlag USE = register(FlagCategory.PROTECTION, new StateFlag("use", true));
    public static final StateFlag CHEST_ACCESS = register(FlagCategory.PROTECTION, new StateFlag("chest-access", true));
    public static final StateFlag PVP = register(FlagCategory.PROTECTION, new StateFlag("pvp", true));
    public static final StateFlag DAMAGE_ANIMALS = register(FlagCategory.PROTECTION, new StateFlag("damage-animals", true));
    public static final StateFlag FALL_DAMAGE = register(FlagCategory.PROTECTION, new StateFlag("fall-damage", true));
    public static final StateFlag RIDE = register(FlagCategory.PROTECTION, new StateFlag("ride", true));
    public static final StateFlag SLEEP = register(FlagCategory.PROTECTION, new StateFlag("sleep", true));
    public static final StateFlag TNT = register(FlagCategory.PROTECTION, new StateFlag("tnt", true));
    public static final StateFlag LIGHTER = register(FlagCategory.PROTECTION, new StateFlag("lighter", true));
    public static final StateFlag END_CRYSTAL_PLACE = register(FlagCategory.PROTECTION, new StateFlag("end-crystal-place", true));
    public static final StateFlag END_CRYSTAL_INTERACT = register(FlagCategory.PROTECTION, new StateFlag("end-crystal-interact", true));
    public static final StateFlag WORLDEDIT = register(FlagCategory.PROTECTION, new StateFlag("worldedit", true));
    public static final StateFlag PISTONS = register(FlagCategory.PROTECTION, new StateFlag("pistons", true));
    public static final StringSetFlag NONPLAYER_PROTECTION_DOMAINS = register(FlagCategory.PROTECTION, new StringSetFlag("nonplayer-protection-domains"));
    /**
     * When allowed, the region does not take part in build protection at all — matching WorldGuard,
     * where a passthrough region is skipped entirely when deciding whether someone may build. Used
     * for regions that exist only to carry a greeting or an effect over an area players build in.
     */
    public static final StateFlag PASSTHROUGH = register(FlagCategory.PROTECTION, new StateFlag("passthrough", false));
    public static final StateFlag ENTITY_ITEM_FRAME_DESTROY = register(FlagCategory.PROTECTION, new StateFlag("entity-item-frame-destroy", true));
    public static final StateFlag ENTITY_PAINTING_DESTROY = register(FlagCategory.PROTECTION, new StateFlag("entity-painting-destroy", true));
    public static final StateFlag ENTITY_ARMOR_STAND_DESTROY = register(FlagCategory.PROTECTION, new StateFlag("entity-armor-stand-destroy", true));
    public static final StateFlag ITEM_FRAME_ROTATION = register(FlagCategory.PROTECTION, new StateFlag("item-frame-rotation", true));
    public static final StateFlag VEHICLE_PLACE = register(FlagCategory.PROTECTION, new StateFlag("vehicle-place", true));
    public static final StateFlag VEHICLE_DESTROY = register(FlagCategory.PROTECTION, new StateFlag("vehicle-destroy", true));
    public static final StateFlag POTION_SPLASH = register(FlagCategory.PROTECTION, new StateFlag("potion-splash", true));
    public static final StateFlag FIREWORK_DAMAGE = register(FlagCategory.PROTECTION, new StateFlag("firework-damage", true));
    public static final StateFlag USE_ANVIL = register(FlagCategory.PROTECTION, new StateFlag("use-anvil", true));
    public static final StateFlag RESPAWN_ANCHORS = register(FlagCategory.PROTECTION, new StateFlag("respawn-anchors", true));
    public static final StateFlag USE_DRIPLEAF = register(FlagCategory.PROTECTION, new StateFlag("use-dripleaf", true));
    public static final StateFlag SIGN_EDIT = register(FlagCategory.PROTECTION, new StateFlag("sign-edit", true));
    public static final StateFlag TNT_PRIME = register(FlagCategory.PROTECTION, new StateFlag("tnt-prime", true));
    public static final StateFlag ARMOR_STAND_MANIPULATE = register(FlagCategory.PROTECTION, new StateFlag("armor-stand-manipulate", true));
    public static final StateFlag MANNEQUIN_MANIPULATE = register(FlagCategory.PROTECTION, new StateFlag("mannequin-manipulate", true));
    public static final StateFlag VAULT_USE = register(FlagCategory.PROTECTION, new StateFlag("vault-use", true));
    public static final StateFlag BUCKET_ENTITY = register(FlagCategory.PROTECTION, new StateFlag("bucket-entity", true));
    public static final StateFlag BUCKET_FILL = register(FlagCategory.PROTECTION, new StateFlag("bucket-fill", true));
    public static final StateFlag BUCKET_EMPTY = register(FlagCategory.PROTECTION, new StateFlag("bucket-empty", true));
    public static final StateFlag SHEAR = register(FlagCategory.PROTECTION, new StateFlag("shear", true));
    public static final StateFlag LEASH = register(FlagCategory.PROTECTION, new StateFlag("leash", true));
    public static final StateFlag NAME_ENTITY = register(FlagCategory.PROTECTION, new StateFlag("name-entity", true));
    public static final StateFlag FLOWER_POT = register(FlagCategory.PROTECTION, new StateFlag("flower-pot", true));
    public static final StateFlag LECTERN = register(FlagCategory.PROTECTION, new StateFlag("lectern", true));
    public static final StateFlag ENTRY = register(FlagCategory.ENTRY, new StateFlag("entry", true));
    public static final StateFlag EXIT = register(FlagCategory.ENTRY, new StateFlag("exit", true));

    // Mobs & explosions.
    public static final StateFlag MOB_SPAWNING = register(FlagCategory.MOBS, new StateFlag("mob-spawning", true));
    public static final StateFlag MOB_DAMAGE = register(FlagCategory.MOBS, new StateFlag("mob-damage", true));
    public static final StateFlag CREEPER_EXPLOSION = register(FlagCategory.MOBS, new StateFlag("creeper-explosion", true));
    public static final StateFlag OTHER_EXPLOSION = register(FlagCategory.MOBS, new StateFlag("other-explosion", true));
    public static final StateFlag ENDERMAN_GRIEF = register(FlagCategory.MOBS, new StateFlag("enderman-grief", true));
    public static final StateFlag GHAST_FIREBALL = register(FlagCategory.MOBS, new StateFlag("ghast-fireball", true));
    public static final StateFlag WITHER_DAMAGE = register(FlagCategory.MOBS, new StateFlag("wither-damage", true));
    public static final StateFlag ENDERDRAGON_BLOCK_DAMAGE = register(FlagCategory.MOBS, new StateFlag("enderdragon-block-damage", true));
    public static final StateFlag RAVAGER_GRIEF = register(FlagCategory.MOBS, new StateFlag("ravager-grief", true));
    public static final StateFlag SNOWMAN_TRAILS = register(FlagCategory.MOBS, new StateFlag("snowman-trails", true));
    public static final StateFlag BREEZE_CHARGE_EXPLOSION = register(FlagCategory.MOBS, new StateFlag("breeze-charge-explosion", true));
    public static final StateFlag LIGHTNING = register(FlagCategory.MOBS, new StateFlag("lightning", true));
    public static final EntityTypeSetFlag DENY_SPAWN = register(FlagCategory.MOBS, new EntityTypeSetFlag("deny-spawn"));
    public static final StateFlag RAID = register(FlagCategory.MOBS, new StateFlag("raid", true));
    public static final StateFlag ENTITY_TRANSFORM = register(FlagCategory.MOBS, new StateFlag("entity-transform", true));
    public static final StateFlag BREED = register(FlagCategory.MOBS, new StateFlag("breed", true));
    public static final StateFlag TAME = register(FlagCategory.MOBS, new StateFlag("tame", true));
    public static final StateFlag DOOR_BREAK = register(FlagCategory.MOBS, new StateFlag("door-break", true));
    public static final StateFlag COPPER_GOLEM = register(FlagCategory.MOBS, new StateFlag("copper-golem", true));

    // Natural events.
    public static final StateFlag FIRE_SPREAD = register(FlagCategory.ENVIRONMENT, new StateFlag("fire-spread", true));
    public static final StateFlag LAVA_FIRE = register(FlagCategory.ENVIRONMENT, new StateFlag("lava-fire", true));
    public static final StateFlag LAVA_FLOW = register(FlagCategory.ENVIRONMENT, new StateFlag("lava-flow", true));
    public static final StateFlag WATER_FLOW = register(FlagCategory.ENVIRONMENT, new StateFlag("water-flow", true));
    public static final StateFlag LAVA_HARDEN = register(FlagCategory.ENVIRONMENT, new StateFlag("lava-harden", true));
    public static final StateFlag SNOW_FALL = register(FlagCategory.ENVIRONMENT, new StateFlag("snow-fall", true));
    public static final StateFlag SNOW_MELT = register(FlagCategory.ENVIRONMENT, new StateFlag("snow-melt", true));
    public static final StateFlag ICE_FORM = register(FlagCategory.ENVIRONMENT, new StateFlag("ice-form", true));
    public static final StateFlag ICE_MELT = register(FlagCategory.ENVIRONMENT, new StateFlag("ice-melt", true));
    public static final StateFlag LEAF_DECAY = register(FlagCategory.ENVIRONMENT, new StateFlag("leaf-decay", true));
    public static final StateFlag CROP_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("crop-growth", true));
    public static final StateFlag VINE_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("vine-growth", true));
    public static final StateFlag CROP_TRAMPLE = register(FlagCategory.ENVIRONMENT, new StateFlag("crop-trample", true));
    public static final StateFlag EGG_TRAMPLE = register(FlagCategory.ENVIRONMENT, new StateFlag("egg-trample", true));
    public static final StateFlag FROSTWALKER = register(FlagCategory.ENVIRONMENT, new StateFlag("frostwalker", true));
    public static final StateFlag FROSTED_ICE_MELT = register(FlagCategory.ENVIRONMENT, new StateFlag("frosted-ice-melt", true));
    public static final StateFlag GRASS_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("grass-growth", true));
    public static final StateFlag MYCELIUM_SPREAD = register(FlagCategory.ENVIRONMENT, new StateFlag("mycelium-spread", true));
    public static final StateFlag MUSHROOM_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("mushroom-growth", true));
    public static final StateFlag SCULK_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("sculk-growth", true));
    public static final StateFlag ROCK_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("rock-growth", true));
    public static final StateFlag CORAL_FADE = register(FlagCategory.ENVIRONMENT, new StateFlag("coral-fade", true));
    public static final StateFlag COPPER_FADE = register(FlagCategory.ENVIRONMENT, new StateFlag("copper-fade", true));
    public static final StateFlag MOISTURE_CHANGE = register(FlagCategory.ENVIRONMENT, new StateFlag("moisture-change", true));
    public static final StateFlag SOIL_DRY = register(FlagCategory.ENVIRONMENT, new StateFlag("soil-dry", true));
    public static final StateFlag TREE_GROWTH = register(FlagCategory.ENVIRONMENT, new StateFlag("tree-growth", true));
    public static final StateFlag SPONGE_ABSORB = register(FlagCategory.ENVIRONMENT, new StateFlag("sponge-absorb", true));
    public static final StateFlag CHUNK_UNLOAD = register(FlagCategory.ENVIRONMENT, new StateFlag("chunk-unload", true));

    // Movement & teleport.
    public static final StateFlag ENDERPEARL = register(FlagCategory.MOVEMENT, new StateFlag("enderpearl", true));
    public static final StateFlag CHORUS_TELEPORT = register(FlagCategory.MOVEMENT, new StateFlag("chorus-fruit-teleport", true));
    public static final StateFlag PORTAL_CREATE = register(FlagCategory.MOVEMENT, new StateFlag("portal-create", true));

    // Messages & effects.
    public static final StringFlag GREETING = register(FlagCategory.MESSAGES, new StringFlag("greeting"));
    public static final StringFlag FAREWELL = register(FlagCategory.MESSAGES, new StringFlag("farewell"));
    public static final StringFlag GREETING_TITLE = register(FlagCategory.MESSAGES, new StringFlag("greeting-title"));
    public static final StringFlag FAREWELL_TITLE = register(FlagCategory.MESSAGES, new StringFlag("farewell-title"));
    public static final StringFlag DENY_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("deny-message"));
    public static final BooleanFlag NOTIFY_ENTER = register(FlagCategory.MESSAGES, new BooleanFlag("notify-enter"));
    public static final BooleanFlag NOTIFY_LEAVE = register(FlagCategory.MESSAGES, new BooleanFlag("notify-leave"));
    public static final StateFlag SEND_CHAT = register(FlagCategory.MESSAGES, new StateFlag("send-chat", true));
    public static final StateFlag RECEIVE_CHAT = register(FlagCategory.MESSAGES, new StateFlag("receive-chat", true));
    public static final StringFlag CHAT_PREFIX = register(FlagCategory.MESSAGES, new StringFlag("chat-prefix"));
    public static final StringFlag CHAT_SUFFIX = register(FlagCategory.MESSAGES, new StringFlag("chat-suffix"));
    public static final StringFlag ENTRY_DENY_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("entry-deny-message"));
    public static final StringFlag EXIT_DENY_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("exit-deny-message"));
    public static final BooleanFlag INVINCIBLE = register(FlagCategory.PLAYER, new BooleanFlag("invincible"));
    public static final DoubleFlag HEAL_AMOUNT = register(FlagCategory.PLAYER, new DoubleFlag("heal-amount"));
    public static final DoubleFlag HEAL_MIN_HEALTH = register(FlagCategory.PLAYER, new DoubleFlag("heal-min-health"));
    public static final DoubleFlag HEAL_MAX_HEALTH = register(FlagCategory.PLAYER, new DoubleFlag("heal-max-health"));
    public static final StringFlag GAME_MODE = register(FlagCategory.PLAYER, new StringFlag("game-mode"));
    public static final PotionEffectSetFlag GIVE_EFFECTS = register(FlagCategory.PLAYER, new PotionEffectSetFlag("give-effects"));
    public static final PotionEffectSetFlag BLOCKED_EFFECTS = register(FlagCategory.PLAYER, new PotionEffectSetFlag("blocked-effects"));
    public static final BooleanFlag HIDE_PLAYERS = register(FlagCategory.PLAYER, new BooleanFlag("hide-players"));
    public static final StateFlag NATURAL_HEALTH_REGEN = register(FlagCategory.PLAYER, new StateFlag("natural-health-regen", true));
    public static final StateFlag NATURAL_HUNGER_DRAIN = register(FlagCategory.PLAYER, new StateFlag("natural-hunger-drain", true));
    public static final IntegerFlag HEAL_DELAY = register(FlagCategory.PLAYER, new IntegerFlag("heal-delay"));
    public static final IntegerFlag FEED_DELAY = register(FlagCategory.PLAYER, new IntegerFlag("feed-delay"));
    public static final IntegerFlag FEED_AMOUNT = register(FlagCategory.PLAYER, new IntegerFlag("feed-amount"));
    public static final IntegerFlag MIN_FOOD = register(FlagCategory.PLAYER, new IntegerFlag("min-food"));
    public static final IntegerFlag MAX_FOOD = register(FlagCategory.PLAYER, new IntegerFlag("max-food"));
    public static final StringFlag TIME_LOCK = register(FlagCategory.PLAYER, new StringFlag("time-lock"));
    public static final StringFlag WEATHER_LOCK = register(FlagCategory.PLAYER, new StringFlag("weather-lock"));

    // Item-use control.
    public static final MaterialSetFlag DISABLE_COMPLETELY = register(FlagCategory.ITEMS, new MaterialSetFlag("disable-completely"));
    public static final BooleanFlag DISABLE_THROW = register(FlagCategory.ITEMS, new BooleanFlag("disable-throw"));
    public static final StateFlag WIND_CHARGE = register(FlagCategory.ITEMS, new StateFlag("wind-charge", true));
    public static final StateFlag VILLAGER_TRADE = register(FlagCategory.ITEMS, new StateFlag("villager-trade", true));
    public static final StateFlag PERMIT_WORKBENCHES = register(FlagCategory.ITEMS, new StateFlag("permit-workbenches", true));
    public static final StateFlag INVENTORY_CRAFT = register(FlagCategory.ITEMS, new StateFlag("inventory-craft", true));
    public static final MaterialSetFlag DENY_ITEM_DROPS = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-item-drops"));
    public static final MaterialSetFlag DENY_ITEM_PICKUP = register(FlagCategory.ITEMS, new MaterialSetFlag("deny-item-pickup"));
    public static final StateFlag ITEM_DROP = register(FlagCategory.ITEMS, new StateFlag("item-drop", true));
    public static final StateFlag ITEM_PICKUP = register(FlagCategory.ITEMS, new StateFlag("item-pickup", true));
    public static final StateFlag CRAFTER = register(FlagCategory.ITEMS, new StateFlag("crafter", true));
    public static final StateFlag HOPPER_TRANSFER = register(FlagCategory.ITEMS, new StateFlag("hopper-transfer", true));
    public static final StateFlag DISPENSE = register(FlagCategory.ITEMS, new StateFlag("dispense", true));
    public static final StateFlag ENCHANT = register(FlagCategory.ITEMS, new StateFlag("enchant", true));
    public static final StateFlag BREW = register(FlagCategory.ITEMS, new StateFlag("brew", true));
    public static final StateFlag SMELT = register(FlagCategory.ITEMS, new StateFlag("smelt", true));

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
    public static final BooleanFlag EXIT_OVERRIDE = register(FlagCategory.ENTRY, new BooleanFlag("exit-override"));
    public static final StateFlag EXIT_VIA_TELEPORT = register(FlagCategory.ENTRY, new StateFlag("exit-via-teleport", true));

    // Enter/leave actions.
    public static final StringFlag TELEPORT = register(FlagCategory.ENTRY, new StringFlag("teleport"));
    public static final StringFlag TELEPORT_MESSAGE = register(FlagCategory.MESSAGES, new StringFlag("teleport-message"));
    public static final StringFlag TELEPORT_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("teleport-on-entry"));
    public static final StringFlag TELEPORT_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("teleport-on-exit"));
    public static final StringFlag COMMAND_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("command-on-entry"));
    public static final StringFlag COMMAND_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("command-on-exit"));
    public static final StringFlag CONSOLE_COMMAND_ON_ENTRY = register(FlagCategory.ENTRY, new StringFlag("console-command-on-entry"));
    public static final StringFlag CONSOLE_COMMAND_ON_EXIT = register(FlagCategory.ENTRY, new StringFlag("console-command-on-exit"));
    public static final StringFlag PLAY_SOUNDS = register(FlagCategory.MESSAGES, new StringFlag("play-sounds"));
    public static final StringFlag RESPAWN_LOCATION = register(FlagCategory.ENTRY, new StringFlag("respawn-location"));
    public static final StringFlag JOIN_LOCATION = register(FlagCategory.ENTRY, new StringFlag("join-location"));

    // Command control. blocked-cmds is a deny-list; allowed-cmds, when set, is an exclusive
    // allow-list — anything not named is refused.
    public static final StringSetFlag BLOCKED_CMDS = register(FlagCategory.ITEMS, new StringSetFlag("blocked-cmds"));
    public static final StringSetFlag ALLOWED_CMDS = register(FlagCategory.ITEMS, new StringSetFlag("allowed-cmds"));

    // Continuous player state while inside.
    public static final DoubleFlag WALK_SPEED = register(FlagCategory.PLAYER, new DoubleFlag("walk-speed"));
    public static final DoubleFlag FLY_SPEED = register(FlagCategory.PLAYER, new DoubleFlag("fly-speed"));
    public static final BooleanFlag FLY = register(FlagCategory.PLAYER, new BooleanFlag("fly"));
    public static final BooleanFlag GODMODE = register(FlagCategory.PLAYER, new BooleanFlag("godmode"));

    // Death / misc toggles.
    public static final BooleanFlag KEEP_INVENTORY = register(FlagCategory.PLAYER, new BooleanFlag("keep-inventory"));
    public static final BooleanFlag KEEP_EXP = register(FlagCategory.PLAYER, new BooleanFlag("keep-exp"));
    public static final StateFlag MOB_DROPS = register(FlagCategory.MOBS, new StateFlag("mob-drops", true));
    public static final StateFlag EXP_DROPS = register(FlagCategory.MOBS, new StateFlag("exp-drops", true));
    public static final StateFlag GLIDE = register(FlagCategory.MOVEMENT, new StateFlag("glide", true));
    public static final StateFlag NETHER_PORTALS = register(FlagCategory.MOVEMENT, new StateFlag("nether-portals", true));
    public static final StateFlag ITEM_DURABILITY = register(FlagCategory.ITEMS, new StateFlag("item-durability", true));
    public static final BooleanFlag DISABLE_COLLISION = register(FlagCategory.PLAYER, new BooleanFlag("disable-collision"));
    public static final StateFlag CHAMBERED_ENDERPEARL = register(FlagCategory.MOVEMENT, new StateFlag("chambered-enderpearl", true));

    static {
        builtInsRegistered = true;
    }

    private Flags() {}

    /**
     * Register a custom flag under the given menu category.
     *
     * @return the flag, for assignment to a constant
     * @throws IllegalStateException if a flag with the same name is already registered
     */
    public static <F extends Flag<?>> F register(final FlagCategory category, final F flag) {
        return register(category, flag, builtInsRegistered ? registeringPlugin() : null);
    }

    /**
     * Internal: registers a flag uWorldGuard itself provides, which is never released. The owner
     * cannot be read off the stack for these: they can be registered lazily from inside another
     * plugin's call, which would credit that plugin with them. Plugins should not call this.
     */
    public static <F extends Flag<?>> F registerInternal(final FlagCategory category, final F flag) {
        return register(category, flag, null);
    }

    private static <F extends Flag<?>> F register(
        final FlagCategory category, final F flag, final @Nullable ClassLoader owner
    ) {
        synchronized (Flags.class) {
            final String key = flag.getName().toLowerCase(Locale.ROOT);
            if (BY_NAME.containsKey(key)) {
                throw new IllegalStateException("A flag named '" + flag.getName() + "' is already registered");
            }
            flag.setCategory(category);
            final Integer reserved = RELEASED.remove(key);
            if (reserved != null) {
                flag.setIndex(reserved);
            } else {
                flag.setIndex(nextIndex);
                nextIndex = nextIndex + 1;
            }
            BY_NAME.put(key, flag);
            if (owner != null) {
                OWNERS.put(flag, owner);
            }

            final List<Flag<?>> updated = new ArrayList<>(ordered);
            updated.add(flag);
            ordered = List.copyOf(updated);
        }
        onRegister.accept(flag);
        return flag;
    }

    /**
     * Internal: takes {@code flag} out of the registry so its name can be registered again, keeping
     * its index for that name. Values already stored under it are the caller's to deal with first.
     * Plugins should not call this.
     *
     * @return false when {@code flag} is not the flag currently registered under its name
     */
    public static synchronized boolean release(final Flag<?> flag) {
        final String key = flag.getName().toLowerCase(Locale.ROOT);
        if (BY_NAME.get(key) != flag) {
            return false;
        }
        BY_NAME.remove(key);
        OWNERS.remove(flag);
        RELEASED.put(key, flag.getIndex());
        final List<Flag<?>> updated = new ArrayList<>(ordered);
        updated.remove(flag);
        ordered = List.copyOf(updated);
        return true;
    }

    /**
     * Internal: the flags registered by code from {@code loader}. Plugins should not call this.
     */
    public static List<Flag<?>> ownedBy(final ClassLoader loader) {
        final List<Flag<?>> owned = new ArrayList<>();
        for (final Map.Entry<Flag<?>, ClassLoader> entry : OWNERS.entrySet()) {
            if (entry.getValue() == loader) {
                owned.add(entry.getKey());
            }
        }
        return owned;
    }

    /**
     * Internal: run after every registration, outside the registry's lock. uWorldGuard uses it to
     * apply stored values that were waiting for the flag. Plugins should not call this.
     */
    public static void onRegister(final Consumer<Flag<?>> hook) {
        onRegister = hook;
    }

    /**
     * The nearest caller loaded by another plugin, which is who owns the flag. Registrations can
     * arrive through uWorldGuard's own code (the WorldGuard API shim forwards here), so frames from
     * this plugin are skipped. A built-in flag has no such caller and so no owner, and is never
     * released.
     */
    private static @Nullable ClassLoader registeringPlugin() {
        final ClassLoader own = Flags.class.getClassLoader();
        return WALKER.walk(frames -> frames
            .map(frame -> frame.getDeclaringClass().getClassLoader())
            .filter(loader -> loader != own && loader instanceof ConfiguredPluginClassLoader)
            .findFirst()
            .orElse(null));
    }

    /**
     * The upper bound on {@link Flag#getIndex()}, for sizing bitsets. Counts released flags too,
     * since their indexes stay reserved.
     */
    public static int count() {
        return nextIndex;
    }

    public static @Nullable Flag<?> get(final String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Every registered flag, in registration order. Immutable and shared, do not copy it defensively.
     */
    public static List<Flag<?>> all() {
        return ordered;
    }
}
