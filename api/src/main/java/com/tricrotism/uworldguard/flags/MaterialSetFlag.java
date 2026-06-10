package com.tricrotism.uworldguard.flags;

import org.bukkit.Material;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * A flag whose value is a set of {@link Material}s — used by item-list flags such as
 * {@code disable-completely}. Parsing accepts a comma-separated list of material names; the
 * special token {@code SPEAR} expands to every spear tier present on the server (any material
 * whose name ends in {@code SPEAR}), so all tiers can be covered without naming each one.
 */
@NullMarked
public final class MaterialSetFlag extends Flag<Set<Material>> {

    public MaterialSetFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Set<Material> parse(final String input) {
        final Set<Material> materials = EnumSet.noneOf(Material.class);
        for (final String raw : input.split(",")) {
            final String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SPEAR")) {
                addSpears(materials);
                continue;
            }
            final Material material = Material.matchMaterial(token);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? null : materials;
    }

    @Override
    public @Nullable Set<Material> unmarshal(final Object stored) {
        if (!(stored instanceof Collection<?> list)) {
            return parse(String.valueOf(stored));
        }
        final Set<Material> materials = EnumSet.noneOf(Material.class);
        for (final Object element : list) {
            final Material material = Material.matchMaterial(String.valueOf(element));
            if (material != null) {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? null : materials;
    }

    @Override
    public Object marshal(final Set<Material> value) {
        final List<String> names = new ArrayList<>(value.size());
        for (final Material material : value) {
            names.add(material.name());
        }
        return names;
    }

    private static void addSpears(final Set<Material> out) {
        for (final Material material : Material.values()) {
            if (material.name().endsWith("SPEAR")) {
                out.add(material);
            }
        }
    }
}
