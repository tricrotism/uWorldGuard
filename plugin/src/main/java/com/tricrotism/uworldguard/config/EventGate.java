package com.tricrotism.uworldguard.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.vehicle.VehicleEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-world event gate. A world may list Bukkit events (by class simple name) that uWorldGuard should
 * not act on; every listener calls {@link #disabled(Event)} first and returns when it is true, so the
 * handler is effectively not listening for that world. Bukkit registers handlers globally per class,
 * so this is a runtime skip rather than literal unregistration. With {@code whitelist-mode} the list
 * inverts: only the listed events are handled and every other is skipped.
 *
 * <p>Configured under {@code worlds.<name>.events} in {@code config.yml}:
 * <pre>
 * worlds:
 *   test:
 *     events:
 *       whitelist-mode: false
 *       disabled:
 *         - BlockBreakEvent
 *         - PlayerInteractEvent
 * </pre>
 *
 * <p>Global, immutable-after-load config read from every region thread: the filter map is published
 * through a {@code volatile} reference and replaced wholesale on reload. The common no-config case is
 * a single empty-map check, so the gate stays off the hot path; event names are cached per class via
 * {@link ClassValue}, so a consulted gate allocates nothing.
 */
@NullMarked
public final class EventGate {

    private record WorldFilter(Set<String> events, boolean whitelist) {
        boolean disabled(final String eventName) {
            return whitelist != events.contains(eventName);
        }
    }

    private static final ClassValue<String> EVENT_NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(final Class<?> type) {
            return type.getSimpleName();
        }
    };

    private static volatile Map<String, WorldFilter> byWorld = Map.of();

    private EventGate() {
    }

    /**
     * (Re)load the per-world event filters from {@code config.yml}.
     */
    public static void load(final FileConfiguration config) {
        final ConfigurationSection worlds = config.getConfigurationSection("worlds");
        if (worlds == null) {
            byWorld = Map.of();
            return;
        }
        final Map<String, WorldFilter> map = new HashMap<>();
        for (final String world : worlds.getKeys(false)) {
            final ConfigurationSection worldSection = worlds.getConfigurationSection(world);
            final ConfigurationSection events =
                worldSection == null ? null : worldSection.getConfigurationSection("events");
            if (events == null) {
                continue;
            }
            final boolean whitelist = events.getBoolean("whitelist-mode", false);
            final List<String> disabled = events.getStringList("disabled");
            // A blacklist with nothing listed disables nothing; a whitelist with nothing listed
            // disables everything, so only the empty blacklist is a no-op worth skipping.
            if (disabled.isEmpty() && !whitelist) {
                continue;
            }
            map.put(world, new WorldFilter(Set.copyOf(disabled), whitelist));
        }
        byWorld = map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    /**
     * Whether uWorldGuard should skip this event because its world disables it. A single empty-map
     * check when no world is configured; allocation-free otherwise.
     */
    public static boolean disabled(final Event event) {
        final Map<String, WorldFilter> map = byWorld;
        if (map.isEmpty()) {
            return false;
        }
        final World world = worldOf(event);
        if (world == null) {
            return false;
        }
        final WorldFilter filter = map.get(world.getName());
        return filter != null && filter.disabled(EVENT_NAMES.get(event.getClass()));
    }

    private static @Nullable World worldOf(final Event event) {
        if (event instanceof PlayerEvent e) {
            return e.getPlayer().getWorld();
        }
        if (event instanceof BlockEvent e) {
            return e.getBlock().getWorld();
        }
        if (event instanceof EntityEvent e) {
            return e.getEntity().getWorld();
        }
        if (event instanceof VehicleEvent e) {
            return e.getVehicle().getWorld();
        }
        if (event instanceof InventoryOpenEvent e) {
            return e.getPlayer().getWorld();
        }
        if (event instanceof InventoryInteractEvent e) {
            return e.getWhoClicked().getWorld();
        }
        return null;
    }
}
