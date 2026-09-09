package com.tricrotism.uworldguard.flags;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * A flag whose value is a set of {@link EntityType}s — used by {@code deny-spawn}. Parsing accepts a
 * comma-separated list of either namespaced keys ({@code minecraft:creeper}) or plain names
 * ({@code CREEPER}), so a list copied out of a WorldGuard region file and one typed by hand both work.
 *
 * <p>Values marshal back as namespaced keys, matching what WorldGuard writes.
 */
@NullMarked
public final class EntityTypeSetFlag extends Flag<Set<EntityType>> {

    public EntityTypeSetFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Set<EntityType> parse(final String input) {
        final Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        for (final String raw : input.split(",")) {
            final EntityType type = entityType(raw);
            if (type != null) {
                types.add(type);
            }
        }
        return types.isEmpty() ? null : types;
    }

    @Override
    public @Nullable Set<EntityType> unmarshal(final Object stored) {
        if (!(stored instanceof Collection<?> list)) {
            return parse(String.valueOf(stored));
        }
        final Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        final List<String> unreadable = new ArrayList<>(0);
        for (final Object element : list) {
            final String token = String.valueOf(element);
            final EntityType type = entityType(token);
            if (type != null) {
                types.add(type);
            } else {
                unreadable.add(token);
            }
        }
        DroppedValues.report(getName(), unreadable);
        return types.isEmpty() ? null : types;
    }

    @Override
    public Object marshal(final Set<EntityType> value) {
        final List<String> keys = new ArrayList<>(value.size());
        for (final EntityType type : value) {
            keys.add(type.getKey().asString());
        }
        return keys;
    }

    private static @Nullable EntityType entityType(final String raw) {
        final String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return null;
        }
        final NamespacedKey key = NamespacedKey.fromString(token);
        if (key == null) {
            return null;
        }
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key);
    }
}
