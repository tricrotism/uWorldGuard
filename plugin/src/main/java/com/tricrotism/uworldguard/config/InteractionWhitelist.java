package com.tricrotism.uworldguard.config;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Blocks that the blanket {@code interact} / {@code use} check never applies to, so a region denying
 * interaction still lets players right-click them. Consulted only by that one check — flags naming a
 * specific block ({@code use-anvil}, {@code permit-workbenches}, {@code chest-access},
 * {@code lectern}, …) keep governing their own blocks, so whitelisting a note block does not also
 * open every chest.
 *
 * <p>Configured globally under {@code interaction.whitelist} and per world under
 * {@code worlds.<name>.interaction.whitelist} in {@code config.yml}. The two add up rather than
 * override: a world's list is what that world allows <em>on top of</em> the global one.
 * <pre>
 * interaction:
 *   whitelist:
 *     - NOTE_BLOCK
 * worlds:
 *   creative:
 *     interaction:
 *       whitelist:
 *         - ANVIL
 * </pre>
 *
 * <p>Read from every region thread on the interact path, so a lookup is one array load off a
 * {@code volatile} reference, and only worlds with a list of their own pay the map lookup after it.
 * Both references are replaced wholesale on reload. The empty case — no whitelist configured, which
 * is most servers — is a null check and an empty-map check.
 */
@NullMarked
public final class InteractionWhitelist {

    private static volatile boolean @Nullable [] global;
    private static volatile Map<String, boolean[]> byWorld = Map.of();

    private InteractionWhitelist() {}

    /**
     * (Re)load the global and per-world whitelists. Names that do not match a material are reported
     * and skipped, rather than failing the load and taking the rest of the list with them.
     */
    public static void load(final FileConfiguration config, final Logger log) {
        global = read(config.getStringList("interaction.whitelist"), "interaction.whitelist", log);

        final ConfigurationSection worlds = config.getConfigurationSection("worlds");
        if (worlds == null) {
            byWorld = Map.of();
            return;
        }
        final Map<String, boolean[]> map = new HashMap<>();
        for (final String world : worlds.getKeys(false)) {
            final ConfigurationSection worldSection = worlds.getConfigurationSection(world);
            if (worldSection == null) {
                continue;
            }
            final boolean[] table = read(worldSection.getStringList("interaction.whitelist"),
                "worlds." + world + ".interaction.whitelist", log);
            if (table != null) {
                map.put(world, table);
            }
        }
        byWorld = map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    /**
     * Whether right-clicking this block in this world skips the {@code interact} / {@code use} check.
     */
    public static boolean allows(final World world, final Material material) {
        final int ordinal = material.ordinal();
        final boolean[] everywhere = global;
        if (everywhere != null && everywhere[ordinal]) {
            return true;
        }
        final Map<String, boolean[]> map = byWorld;
        if (map.isEmpty()) {
            return false;
        }
        final boolean[] here = map.get(world.getName());
        return here != null && here[ordinal];
    }

    /**
     * @return the materials as a lookup table, or {@code null} if the list was empty or held nothing
     * recognizable — either way there is nothing to check, which the lookup answers with one null test
     */
    private static boolean @Nullable [] read(
        final List<String> names, final String path, final Logger log
    ) {
        if (names.isEmpty()) {
            return null;
        }
        final boolean[] table = new boolean[Material.values().length];
        boolean any = false;
        for (final String name : names) {
            final Material material = Material.matchMaterial(name);
            if (material == null) {
                log.warning("Unknown material in " + path + ", ignoring it: " + name);
                continue;
            }
            table[material.ordinal()] = true;
            any = true;
        }
        return any ? table : null;
    }
}
