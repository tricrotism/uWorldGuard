package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.wgcompat.FlagBridge;
import com.tricrotism.uworldguard.wgcompat.SessionBridge;
import com.tricrotism.uworldguard.wgcompat.WgCompatBridge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets a plugin's flags come and go while regions stay loaded, which is what a hot-swap tool such as
 * Cork does to a plugin: disable it, close its classloader, load a fresh copy and enable that.
 *
 * <p>When a plugin disables, any WorldGuard session handlers it registered are unregistered, and
 * each flag it registered is detached. Its value on every region is
 * marshalled back to the stored form and kept alongside the region, then the flag leaves the
 * registry. This has to happen here, in the disable event: the plugin's classloader is still open,
 * so its flag can still marshal, and nothing afterwards holds a reference to its classes. When a
 * flag registers, stored values waiting under its name are read back in.
 *
 * <p>The stored form never changes, so neither step needs a save for correctness. The regions are
 * marked dirty anyway, because that is what rebuilds each world's flag index.
 */
@NullMarked
public final class FlagLifecycle implements Listener {

    private static final Logger LOG = Logger.getLogger("uWorldGuard");

    private final Plugin plugin;
    private final RegionContainerImpl container;

    public FlagLifecycle(final Plugin plugin, final RegionContainerImpl container) {
        this.plugin = plugin;
        this.container = container;
    }

    public void install() {
        Flags.onRegister(this::resolve);
    }

    public void uninstall() {
        Flags.onRegister(_ -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(final PluginDisableEvent event) {
        final Plugin disabling = event.getPlugin();
        if (disabling == plugin) {
            return;
        }
        final ClassLoader loader = disabling.getClass().getClassLoader();
        if (WgCompatBridge.active() && SessionBridge.INSTANCE.releaseHandlersOwnedBy(loader)) {
            LOG.info("Unregistered the WorldGuard session handlers of " + disabling.getName() + ".");
        }
        final List<Flag<?>> owned = Flags.ownedBy(loader);
        if (owned.isEmpty()) {
            return;
        }
        final List<RegionManager> managers = container.managers();
        final List<String> released = new ArrayList<>(owned.size());
        for (final Flag<?> flag : owned) {
            if (!detach(flag, managers)) {
                continue;
            }
            Flags.release(flag);
            if (WgCompatBridge.active()) {
                FlagBridge.releaseConsumerFlag(flag);
            }
            released.add(flag.getName());
        }
        if (!released.isEmpty()) {
            LOG.info("Released " + released.size() + " flag(s) registered by " + disabling.getName()
                + ": " + String.join(", ", released) + ". Their region values are kept and come back"
                + " when the flags register again.");
        }
    }

    /**
     * Applies stored values waiting for {@code flag} in every loaded world.
     */
    private void resolve(final Flag<?> flag) {
        for (final RegionManager manager : container.managers()) {
            resolve(manager, flag);
        }
    }

    /**
     * Applies every stored value in {@code manager} whose flag has since registered. For a world
     * whose load finished after those flags registered, which the registration pass could not see.
     */
    public static void resolvePending(final RegionManager manager) {
        final Set<Flag<?>> registered = new HashSet<>();
        for (final ProtectedRegion region : manager.getRegions()) {
            for (final String key : region.getUnresolvedFlags().keySet()) {
                final Flag<?> flag = Flags.get(key.endsWith("-group")
                    ? key.substring(0, key.length() - "-group".length())
                    : key);
                if (flag != null) {
                    registered.add(flag);
                }
            }
        }
        for (final Flag<?> flag : registered) {
            resolve(manager, flag);
        }
    }

    private static void resolve(final RegionManager manager, final Flag<?> flag) {
        final String name = flag.getName();
        final String groupKey = name + "-group";
        boolean changed = false;
        for (final ProtectedRegion region : manager.getRegions()) {
            final Object raw = region.getUnresolvedFlags().get(name);
            if (raw != null && store(region, flag, raw)) {
                region.removeUnresolvedFlag(name);
                changed = true;
            }
            final Object rawGroup = region.getUnresolvedFlags().get(groupKey);
            if (rawGroup != null) {
                final RegionGroup group = RegionGroup.parse(String.valueOf(rawGroup));
                if (group != null) {
                    region.setFlagGroup(flag, group);
                    region.removeUnresolvedFlag(groupKey);
                    changed = true;
                }
            }
        }
        if (changed) {
            manager.markDirty();
        }
    }

    /**
     * Stores the value first and drops the kept copy second, in both directions, so a save running
     * in between writes the value at least once and never not at all.
     */
    @SuppressWarnings("unchecked")
    private static boolean store(final ProtectedRegion region, final Flag<?> flag, final Object raw) {
        final Object value;
        try {
            value = ((Flag<Object>) flag).unmarshal(raw);
        } catch (final RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "Flag '" + flag.getName() + "' threw reading the value stored on"
                + " region '" + region.getId() + "'. It stays stored as written.", e);
            return false;
        }
        if (value == null) {
            return false;
        }
        region.setFlag((Flag<Object>) flag, value);
        return true;
    }

    /**
     * Moves {@code flag}'s values on every region back to their stored form. Marshals everything
     * before touching any region: the flag is another plugin's code, and one that throws partway
     * leaves it registered with its values in place, rather than half-detached.
     *
     * @return whether the flag was detached and can be released
     */
    @SuppressWarnings("unchecked")
    private static boolean detach(final Flag<?> flag, final List<RegionManager> managers) {
        final String name = flag.getName();
        final List<ProtectedRegion> regions = new ArrayList<>();
        final List<Object> raws = new ArrayList<>();
        try {
            for (final RegionManager manager : managers) {
                for (final ProtectedRegion region : manager.getRegions()) {
                    final Object value = region.getFlags().get(flag);
                    if (value != null) {
                        regions.add(region);
                        raws.add(((Flag<Object>) flag).marshal(value));
                    }
                }
            }
        } catch (final RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "Flag '" + name + "' threw while saving its region values, so it"
                + " stays registered. Reloading the plugin that owns it will fail to register it again"
                + " until a restart.", e);
            return false;
        }
        for (int i = 0; i < regions.size(); i++) {
            final ProtectedRegion region = regions.get(i);
            region.putUnresolvedFlag(name, raws.get(i));
            region.setFlag((Flag<Object>) flag, null);
        }
        for (final RegionManager manager : managers) {
            boolean changed = false;
            for (final ProtectedRegion region : manager.getRegions()) {
                final RegionGroup group = region.getFlagGroups().get(flag);
                if (group != null) {
                    region.putUnresolvedFlag(name + "-group", group.serialized());
                    region.setFlagGroup(flag, null);
                    changed = true;
                }
                changed |= region.getUnresolvedFlags().containsKey(name);
            }
            if (changed) {
                manager.markDirty();
            }
        }
        return true;
    }
}
