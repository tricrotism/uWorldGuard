// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.tricrotism.uworldguard.flags.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds every WorldGuard shim flag to the uWorldGuard engine flag that actually stores and enforces
 * its value, together with the converters that translate between the two value representations.
 *
 * <p>The binding lives in a field on the shim flag itself rather than in a map, so a flag query
 * costs one field read.
 *
 * <p>Parameters are typed {@link Object} so this class never imports {@code com.sk89q}; the shim
 * side is referenced by fully-qualified name and cast internally.
 */
@NullMarked
public final class FlagBridge {

    /**
     * Converts a single flag value between the shim and engine representations.
     */
    public interface Conv {

        @Nullable Object apply(Object value);
    }

    /**
     * What a shim flag is bound to: the engine flag plus the two value converters. A {@code null}
     * converter means the representations are identical.
     */
    public static final class Binding {

        final Flag<Object> engine;
        final @Nullable Conv toShim;
        final @Nullable Conv toEngine;

        Binding(final Flag<Object> engine, final @Nullable Conv toShim, final @Nullable Conv toEngine) {
            this.engine = engine;
            this.toShim = toShim;
            this.toEngine = toEngine;
        }
    }

    private static final Conv STATE_TO_SHIM = value -> value == State.ALLOW
        ? com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW
        : com.sk89q.worldguard.protection.flags.StateFlag.State.DENY;

    private static final Conv STATE_TO_ENGINE = value ->
        value == com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW ? State.ALLOW : State.DENY;

    private static final Conv BOOL_TO_STATE = value -> Boolean.TRUE.equals(value)
        ? com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW
        : com.sk89q.worldguard.protection.flags.StateFlag.State.DENY;

    private static final Conv STATE_TO_BOOL = value ->
        value == com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW ? Boolean.TRUE : Boolean.FALSE;

    private static final Conv TO_INT = value -> ((Number) value).intValue();

    private static final Conv TO_DOUBLE = value -> ((Number) value).doubleValue();

    private static final Map<String, Object> SHIM_BY_ENGINE_NAME = new ConcurrentHashMap<>(256);

    private static volatile boolean bound;

    private FlagBridge() {
    }

    /**
     * Binds the built-in shim flags and registers dormant engine flags for the WorldGuard flags
     * uWorldGuard has no counterpart for. Idempotent; safe to call from any thread.
     */
    public static void registerDormantFlags() {
        ensureBound();
    }

    /**
     * The engine flag a shim flag delegates to, or {@code null} when it is not bridged.
     */
    public static @Nullable Flag<Object> engineFlag(final Object shimFlag) {
        ensureBound();
        final Object binding = ((com.sk89q.worldguard.protection.flags.Flag<?>) shimFlag).uwgBinding();
        return binding == null ? null : ((Binding) binding).engine;
    }

    /**
     * Translates an engine-side value into the representation the shim flag's consumers expect.
     */
    public static @Nullable Object toShimValue(final Object shimFlag, final @Nullable Object engineValue) {
        if (engineValue == null) {
            return null;
        }
        final Object binding = ((com.sk89q.worldguard.protection.flags.Flag<?>) shimFlag).uwgBinding();
        if (binding == null) {
            return engineValue;
        }
        final Conv conv = ((Binding) binding).toShim;
        return conv == null ? engineValue : conv.apply(engineValue);
    }

    /**
     * Translates a shim-side value into the representation the engine flag stores.
     */
    public static @Nullable Object toEngineValue(final Object shimFlag, final @Nullable Object shimValue) {
        if (shimValue == null) {
            return null;
        }
        final Object binding = ((com.sk89q.worldguard.protection.flags.Flag<?>) shimFlag).uwgBinding();
        if (binding == null) {
            return shimValue;
        }
        final Conv conv = ((Binding) binding).toEngine;
        return conv == null ? shimValue : conv.apply(shimValue);
    }

    public static RegionGroup toEngineGroup(final @Nullable Object shimGroup) {
        return shimGroup == null ? RegionGroup.ALL : RegionGroup.valueOf(((Enum<?>) shimGroup).name());
    }

    /**
     * The two enums declare their constants in different orders, so this is a table rather than an
     * ordinal cast — and a table rather than {@code valueOf}, which hashes a string into the enum's
     * constant directory on a path that runs once per region per flag.
     */
    private static final com.sk89q.worldguard.protection.flags.RegionGroup[] SHIM_GROUPS = shimGroups();

    private static com.sk89q.worldguard.protection.flags.RegionGroup[] shimGroups() {
        final RegionGroup[] engine = RegionGroup.values();
        final com.sk89q.worldguard.protection.flags.RegionGroup[] shim =
            new com.sk89q.worldguard.protection.flags.RegionGroup[engine.length];
        for (int i = 0; i < engine.length; i++) {
            shim[i] = com.sk89q.worldguard.protection.flags.RegionGroup.valueOf(engine[i].name());
        }
        return shim;
    }

    public static Object toShimGroup(final RegionGroup group) {
        return SHIM_GROUPS[group.ordinal()];
    }

    /**
     * The shim flag representing an engine flag, creating and binding one on first use. Lets
     * consumers that introspect the registry see uWorldGuard flags WorldGuard never had.
     */
    public static Object wrapEngineFlag(final Flag<?> engineFlag) {
        ensureBound();
        final Object existing = SHIM_BY_ENGINE_NAME.get(engineFlag.getName());
        return existing != null ? existing : SHIM_BY_ENGINE_NAME.computeIfAbsent(engineFlag.getName(),
            name -> createShim(engineFlag));
    }

    /**
     * Registers a flag a third-party plugin created against the WorldGuard API with the engine.
     *
     * @throws IllegalStateException if the engine already has a flag by that name
     */
    public static void registerConsumerFlag(final Object shimFlag) {
        ensureBound();
        @SuppressWarnings("unchecked") final com.sk89q.worldguard.protection.flags.Flag<Object> flag =
            (com.sk89q.worldguard.protection.flags.Flag<Object>) shimFlag;
        final BridgedConsumerFlag<Object> wrapper = new BridgedConsumerFlag<>(flag);
        com.tricrotism.uworldguard.flags.Flags.register(FlagCategory.EXTENSION, wrapper);
        flag.uwgBind(new Binding(wrapper, null, null));
        SHIM_BY_ENGINE_NAME.putIfAbsent(wrapper.getName(), flag);
    }

    /**
     * Drops what the shim holds for a released third-party flag: the mapping from its engine name to
     * the consumer's flag object, and the WorldGuard registry's entry. Both reference classes of the
     * plugin being unloaded, and the registry entry alone would make its next registration a
     * conflict. A flag that did not come through the WorldGuard API is left alone.
     */
    public static void releaseConsumerFlag(final Flag<?> engineFlag) {
        if (!(engineFlag instanceof BridgedConsumerFlag<?>)) {
            return;
        }
        SHIM_BY_ENGINE_NAME.remove(engineFlag.getName());
        if (com.sk89q.worldguard.WorldGuard.getInstance().getFlagRegistry()
            instanceof com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry registry) {
            registry.uwgForget(engineFlag.getName());
        }
    }

    /**
     * Whether the engine flag under this name came from a third-party registration rather than
     * uWorldGuard itself. Two plugins claiming one name is a real conflict; a plugin claiming a name
     * uWorldGuard already implements is not.
     */
    public static boolean isConsumerFlag(final String name) {
        return com.tricrotism.uworldguard.flags.Flags.get(name) instanceof BridgedConsumerFlag<?>;
    }

    private static void ensureBound() {
        if (!bound) {
            bindAll();
        }
    }

    private static synchronized void bindAll() {
        if (bound) {
            return;
        }
        registerDormant();
        for (final com.sk89q.worldguard.protection.flags.Flag<?> shim
            : com.sk89q.worldguard.protection.flags.Flags.uwgAll()) {
            bind(shim);
        }
        bound = true;
    }

    private static void registerDormant() {
        dormant(new StateFlag("item-frame-rotation", true));
        dormant(new StateFlag("lava-harden", true));
        dormant(new StringSetFlag("nonplayer-protection-domains"));
        dormant(new StringFlag("teleport"));
        dormant(new StringFlag("teleport-message"));
    }

    private static void dormant(final Flag<?> flag) {
        if (com.tricrotism.uworldguard.flags.Flags.get(flag.getName()) != null) {
            return;
        }
        try {
            com.tricrotism.uworldguard.flags.Flags.registerInternal(FlagCategory.PROTECTION, flag);
        } catch (final IllegalStateException raced) {
            // Another thread registered it first; the existing flag is equivalent.
        }
    }

    private static void bind(final com.sk89q.worldguard.protection.flags.Flag<?> shim) {
        final String name = shim.getName();
        final Flag<?> engine = WgFlagNames.resolve(name);
        if (engine == null) {
            CompatDiagnostics.stub("flag:" + name);
            return;
        }
        switch (name) {
            case "invincible" -> attach(shim, engine, BOOL_TO_STATE, STATE_TO_BOOL);
            case "heal-amount" -> attach(shim, engine, TO_INT, TO_DOUBLE);
            case "game-mode" -> attach(shim, engine, WeCodecs::gameModeToShim, WeCodecs::gameModeToEngine);
            case "weather-lock" -> attach(shim, engine, WeCodecs::weatherToShim, WeCodecs::weatherToEngine);
            case "deny-spawn" -> attach(shim, engine, WeCodecs::entityTypesToShim, WeCodecs::entityTypesToEngine);
            case "spawn", "teleport" -> attach(shim, engine, WeCodecs::locationToShim, WeCodecs::locationToEngine);
            default -> attachByType(shim, engine);
        }
        SHIM_BY_ENGINE_NAME.putIfAbsent(engine.getName(), shim);
    }

    private static void attachByType(final com.sk89q.worldguard.protection.flags.Flag<?> shim, final Flag<?> engine) {
        if (engine instanceof StateFlag) {
            attach(shim, engine, STATE_TO_SHIM, STATE_TO_ENGINE);
        } else if (isForeignSet(engine)) {
            attach(shim, engine, setToShim(engine), setToEngine(engine));
        } else {
            attach(shim, engine, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static void attach(
        final com.sk89q.worldguard.protection.flags.Flag<?> shim,
        final Flag<?> engine,
        final @Nullable Conv toShim,
        final @Nullable Conv toEngine
    ) {
        shim.uwgBind(new Binding((Flag<Object>) engine, toShim, toEngine));
    }

    private static boolean isForeignSet(final Flag<?> engine) {
        return engine instanceof MaterialSetFlag
            || engine instanceof PotionEffectSetFlag
            || engine instanceof EntityTypeSetFlag;
    }

    @SuppressWarnings("unchecked")
    private static Conv setToShim(final Flag<?> engine) {
        final Flag<Object> typed = (Flag<Object>) engine;
        return value -> {
            final Object marshalled = typed.marshal(value);
            if (!(marshalled instanceof Collection<?> elements)) {
                return marshalled == null ? null : Set.of(String.valueOf(marshalled));
            }
            final Set<String> out = new LinkedHashSet<>(elements.size());
            for (final Object element : elements) {
                out.add(String.valueOf(element));
            }
            return out;
        };
    }

    private static Conv setToEngine(final Flag<?> engine) {
        return value -> {
            if (!(value instanceof Collection<?> elements)) {
                return null;
            }
            final StringJoiner joiner = new StringJoiner(",");
            for (final Object element : elements) {
                joiner.add(String.valueOf(element));
            }
            return engine.parse(joiner.toString());
        };
    }

    @SuppressWarnings("unchecked")
    private static Conv stringToShim(final Flag<?> engine) {
        final Flag<Object> typed = (Flag<Object>) engine;
        return value -> String.valueOf(typed.marshal(value));
    }

    private static Conv stringToEngine(final Flag<?> engine) {
        return value -> engine.parse(String.valueOf(value));
    }

    private static Object createShim(final Flag<?> engine) {
        final String name = engine.getName();
        final com.sk89q.worldguard.protection.flags.RegionGroup group =
            com.sk89q.worldguard.protection.flags.RegionGroup.ALL;
        if (engine instanceof StateFlag stateFlag) {
            final com.sk89q.worldguard.protection.flags.StateFlag shim =
                new com.sk89q.worldguard.protection.flags.StateFlag(name, stateFlag.getDefault() == State.ALLOW, group);
            attach(shim, engine, STATE_TO_SHIM, STATE_TO_ENGINE);
            return shim;
        }
        if (engine instanceof BooleanFlag) {
            final com.sk89q.worldguard.protection.flags.BooleanFlag shim =
                new com.sk89q.worldguard.protection.flags.BooleanFlag(name, group);
            attach(shim, engine, null, null);
            return shim;
        }
        if (engine instanceof IntegerFlag) {
            final com.sk89q.worldguard.protection.flags.IntegerFlag shim =
                new com.sk89q.worldguard.protection.flags.IntegerFlag(name, group);
            attach(shim, engine, null, null);
            return shim;
        }
        if (engine instanceof DoubleFlag) {
            final com.sk89q.worldguard.protection.flags.DoubleFlag shim =
                new com.sk89q.worldguard.protection.flags.DoubleFlag(name, group);
            attach(shim, engine, null, null);
            return shim;
        }
        if (engine instanceof StringSetFlag) {
            final com.sk89q.worldguard.protection.flags.SetFlag<String> shim =
                new com.sk89q.worldguard.protection.flags.SetFlag<>(name, group,
                    new com.sk89q.worldguard.protection.flags.StringFlag(name));
            attach(shim, engine, null, null);
            return shim;
        }
        if (isForeignSet(engine)) {
            final com.sk89q.worldguard.protection.flags.SetFlag<String> shim =
                new com.sk89q.worldguard.protection.flags.SetFlag<>(name, group,
                    new com.sk89q.worldguard.protection.flags.StringFlag(name));
            attach(shim, engine, setToShim(engine), setToEngine(engine));
            return shim;
        }
        final com.sk89q.worldguard.protection.flags.StringFlag shim =
            new com.sk89q.worldguard.protection.flags.StringFlag(name, group);
        attach(shim, engine, engine instanceof StringFlag ? null : stringToShim(engine),
            engine instanceof StringFlag ? null : stringToEngine(engine));
        return shim;
    }
}
