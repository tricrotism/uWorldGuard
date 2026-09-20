// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * WorldGuard's built-in flag table. Each constant is bridged to the uWorldGuard engine flag of the
 * same (or aliased) name by {@code com.tricrotism.uworldguard.wgcompat.FlagBridge}.
 *
 * <p>This class is deliberately loadable on a server with no WorldEdit installed: the four
 * WorldEdit-valued constants are built without resolving any WorldEdit class, and their registries
 * and converters resolve on first use.
 */
public final class Flags {

    private static final List<Flag<?>> ALL = new ArrayList<>(112);
    /**
     * Built once over {@link #ALL} rather than per call: the list is fixed after class init, and the
     * callers walk it at boot.
     */
    private static final List<Flag<?>> ALL_VIEW = java.util.Collections.unmodifiableList(ALL);

    public static final StateFlag PASSTHROUGH = add(state("passthrough", false));
    public static final SetFlag<String> NONPLAYER_PROTECTION_DOMAINS = add(strings("nonplayer-protection-domains"));
    public static final StateFlag BUILD = add(state("build", true));
    public static final StateFlag INTERACT = add(state("interact", true));
    public static final StateFlag BLOCK_BREAK = add(state("block-break", true));
    public static final StateFlag BLOCK_PLACE = add(state("block-place", true));
    public static final StateFlag USE = add(state("use", true));
    public static final StateFlag DAMAGE_ANIMALS = add(state("damage-animals", true));
    public static final StateFlag CHEST_ACCESS = add(state("chest-access", true));
    public static final StateFlag RIDE = add(state("ride", true));
    public static final StateFlag PVP = add(state("pvp", true));
    public static final StateFlag SLEEP = add(state("sleep", true));
    public static final StateFlag RESPAWN_ANCHORS = add(state("respawn-anchors", true));
    public static final StateFlag TNT = add(state("tnt", true));
    public static final StateFlag PLACE_VEHICLE = add(state("vehicle-place", true));
    public static final StateFlag DESTROY_VEHICLE = add(state("vehicle-destroy", true));
    public static final StateFlag LIGHTER = add(state("lighter", true));
    public static final StateFlag TRAMPLE_BLOCKS = add(state("block-trampling", true));
    public static final StateFlag FROSTED_ICE_FORM = add(state("frosted-ice-form", true));
    public static final StateFlag ITEM_FRAME_ROTATE = add(state("item-frame-rotation", true));
    public static final StateFlag FIREWORK_DAMAGE = add(state("firework-damage", true));
    public static final StateFlag USE_ANVIL = add(state("use-anvil", true));
    public static final StateFlag USE_DRIPLEAF = add(state("use-dripleaf", true));

    public static final StateFlag CREEPER_EXPLOSION = add(state("creeper-explosion", true));
    public static final StateFlag ENDERDRAGON_BLOCK_DAMAGE = add(state("enderdragon-block-damage", true));
    public static final StateFlag GHAST_FIREBALL = add(state("ghast-fireball", true));
    public static final StateFlag OTHER_EXPLOSION = add(state("other-explosion", true));
    public static final StateFlag FIRE_SPREAD = add(state("fire-spread", true));
    public static final StateFlag ENDER_BUILD = add(state("enderman-grief", true));
    public static final StateFlag SNOWMAN_TRAILS = add(state("snowman-trails", true));
    public static final StateFlag RAVAGER_RAVAGE = add(state("ravager-grief", true));
    public static final StateFlag MOB_DAMAGE = add(state("mob-damage", true));
    public static final StateFlag MOB_SPAWNING = add(state("mob-spawning", true));
    public static final SetFlag<EntityType> DENY_SPAWN = add(denySpawn());
    public static final StateFlag ENTITY_PAINTING_DESTROY = add(state("entity-painting-destroy", true));
    public static final StateFlag ENTITY_ITEM_FRAME_DESTROY = add(state("entity-item-frame-destroy", true));
    public static final StateFlag WITHER_DAMAGE = add(state("wither-damage", true));
    public static final StateFlag BREEZE_WIND_CHARGE = add(state("breeze-charge-explosion", true));
    public static final StateFlag WIND_CHARGE_BURST = add(state("wind-charge-burst", true));

    public static final StateFlag LAVA_FIRE = add(state("lava-fire", true));
    public static final StateFlag LIGHTNING = add(state("lightning", true));
    public static final StateFlag WATER_FLOW = add(state("water-flow", true));
    public static final StateFlag LAVA_FLOW = add(state("lava-flow", true));
    public static final StateFlag SNOW_FALL = add(state("snow-fall", true));
    public static final StateFlag SNOW_MELT = add(state("snow-melt", true));
    public static final StateFlag ICE_FORM = add(state("ice-form", true));
    public static final StateFlag ICE_MELT = add(state("ice-melt", true));
    public static final StateFlag FROSTED_ICE_MELT = add(state("frosted-ice-melt", true));
    public static final StateFlag MUSHROOMS = add(state("mushroom-growth", true));
    public static final StateFlag LEAF_DECAY = add(state("leaf-decay", true));
    public static final StateFlag GRASS_SPREAD = add(state("grass-growth", true));
    public static final StateFlag MYCELIUM_SPREAD = add(state("mycelium-spread", true));
    public static final StateFlag VINE_GROWTH = add(state("vine-growth", true));
    public static final StateFlag ROCK_GROWTH = add(state("rock-growth", true));
    public static final StateFlag SCULK_GROWTH = add(state("sculk-growth", true));
    public static final StateFlag CROP_GROWTH = add(state("crop-growth", true));
    public static final StateFlag SOIL_DRY = add(state("soil-dry", true));
    public static final StateFlag CORAL_FADE = add(state("coral-fade", true));
    public static final StateFlag COPPER_FADE = add(state("copper-fade", true));
    public static final StateFlag MOISTURE_CHANGE = add(state("moisture-change", true));
    // LOW-CONFIDENCE name — not present in WorldGuard's published flag reference.
    public static final StateFlag LAVA_HARDEN = add(state("lava-harden", true));

    public static final StateFlag ENTRY = add(state("entry", true));
    public static final StateFlag EXIT = add(state("exit", true));
    public static final StateFlag EXIT_VIA_TELEPORT = add(state("exit-via-teleport", true));
    public static final BooleanFlag EXIT_OVERRIDE = add(new BooleanFlag("exit-override", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag ENTRY_DENY_MESSAGE = add(new StringFlag("entry-deny-message", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag EXIT_DENY_MESSAGE = add(new StringFlag("exit-deny-message", RegionGroup.ALL));
    public static final BooleanFlag NOTIFY_ENTER = add(new BooleanFlag("notify-enter", RegionGroup.ALL));
    public static final BooleanFlag NOTIFY_LEAVE = add(new BooleanFlag("notify-leave", RegionGroup.ALL));

    @Deprecated
    public static final StringFlag GREET_MESSAGE = add(new StringFlag("greeting", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag GREET_TITLE = add(new StringFlag("greeting-title", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag FAREWELL_MESSAGE = add(new StringFlag("farewell", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag FAREWELL_TITLE = add(new StringFlag("farewell-title", RegionGroup.ALL));

    public static final StateFlag ENDERPEARL = add(state("enderpearl", true));
    public static final StateFlag CHORUS_TELEPORT = add(state("chorus-fruit-teleport", true));
    public static final LocationFlag TELE_LOC = add(new LocationFlag("teleport", RegionGroup.ALL));
    public static final LocationFlag SPAWN_LOC = add(new LocationFlag("spawn", RegionGroup.ALL));
    @Deprecated
    public static final StringFlag TELE_MESSAGE = add(new StringFlag("teleport-message", RegionGroup.ALL));

    public static final StateFlag ITEM_PICKUP = add(state("item-pickup", true));
    public static final StateFlag ITEM_DROP = add(state("item-drop", true));
    public static final StateFlag EXP_DROPS = add(state("exp-drops", true));
    @Deprecated
    public static final StringFlag DENY_MESSAGE = add(new StringFlag("deny-message", RegionGroup.ALL));

    public static final StateFlag INVINCIBILITY = add(state("invincible", false));
    public static final StateFlag FALL_DAMAGE = add(state("fall-damage", true));
    public static final RegistryFlag<GameMode> GAME_MODE = add(gameMode());
    public static final StringFlag TIME_LOCK = add(new StringFlag("time-lock", RegionGroup.ALL));
    public static final RegistryFlag<WeatherType> WEATHER_LOCK = add(weatherLock());

    public static final StateFlag HEALTH_REGEN = add(state("natural-health-regen", true));
    public static final StateFlag HUNGER_DRAIN = add(state("natural-hunger-drain", true));
    public static final IntegerFlag HEAL_DELAY = add(new IntegerFlag("heal-delay", RegionGroup.ALL));
    public static final IntegerFlag HEAL_AMOUNT = add(new IntegerFlag("heal-amount", RegionGroup.ALL));
    public static final DoubleFlag MIN_HEAL = add(new DoubleFlag("heal-min-health", RegionGroup.ALL));
    public static final DoubleFlag MAX_HEAL = add(new DoubleFlag("heal-max-health", RegionGroup.ALL));
    public static final IntegerFlag FEED_DELAY = add(new IntegerFlag("feed-delay", RegionGroup.ALL));
    public static final IntegerFlag FEED_AMOUNT = add(new IntegerFlag("feed-amount", RegionGroup.ALL));
    public static final IntegerFlag MIN_FOOD = add(new IntegerFlag("feed-min-hunger", RegionGroup.ALL));
    public static final IntegerFlag MAX_FOOD = add(new IntegerFlag("feed-max-hunger", RegionGroup.ALL));

    public static final SetFlag<String> BLOCKED_CMDS = add(strings("blocked-cmds"));
    public static final SetFlag<String> ALLOWED_CMDS = add(strings("allowed-cmds"));
    public static final StateFlag PISTONS = add(state("pistons", true));
    public static final StateFlag SEND_CHAT = add(state("send-chat", true));
    public static final StateFlag RECEIVE_CHAT = add(state("receive-chat", true));
    public static final StateFlag POTION_SPLASH = add(state("potion-splash", true));

    /**
     * The names of every built-in flag, in declaration order.
     */
    public static final List<String> INBUILT_FLAGS = names();

    private Flags() {
    }

    /**
     * Resolve a flag by name, tolerating underscores and unique prefixes.
     */
    public static Flag<?> fuzzyMatchFlag(final FlagRegistry flagRegistry, final String id) {
        final String needle = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        Flag<?> prefixMatch = null;
        int prefixMatches = 0;
        for (final Flag<?> flag : flagRegistry.getAll()) {
            final String name = flag.getName().toLowerCase(Locale.ROOT);
            if (name.equals(needle) || name.replace('-', '_').equals(needle)) {
                return flag;
            }
            if (name.startsWith(needle)) {
                prefixMatch = flag;
                prefixMatches++;
            }
        }
        return prefixMatches == 1 ? prefixMatch : null;
    }

    /**
     * Binds the built-in flags to the uWorldGuard engine. Idempotent.
     */
    public static void registerAll() {
        com.tricrotism.uworldguard.wgcompat.FlagBridge.bindFlags();
    }

    /**
     * Internal: every built-in flag, in declaration order. Not part of the WorldGuard API.
     */
    public static List<Flag<?>> uwgAll() {
        return ALL_VIEW;
    }

    private static <F extends Flag<?>> F add(final F flag) {
        ALL.add(flag);
        return flag;
    }

    private static StateFlag state(final String name, final boolean def) {
        return new StateFlag(name, def, RegionGroup.ALL);
    }

    private static SetFlag<String> strings(final String name) {
        return new SetFlag<>(name, RegionGroup.ALL, new StringFlag(name));
    }

    private static SetFlag<EntityType> denySpawn() {
        return new SetFlag<>("deny-spawn", RegionGroup.ALL,
            new RegistryFlag<EntityType>("deny-spawn", null, (Supplier<Object>) () -> LazyRegistries.entityTypes()));
    }

    private static RegistryFlag<GameMode> gameMode() {
        return new RegistryFlag<>("game-mode", RegionGroup.ALL, (Supplier<Object>) () -> LazyRegistries.gameModes());
    }

    private static RegistryFlag<WeatherType> weatherLock() {
        return new RegistryFlag<>("weather-lock", RegionGroup.ALL, (Supplier<Object>) () -> LazyRegistries.weatherTypes());
    }

    private static List<String> names() {
        final List<String> names = new ArrayList<>(ALL.size());
        for (final Flag<?> flag : ALL) {
            names.add(flag.getName());
        }
        return List.copyOf(names);
    }
}
