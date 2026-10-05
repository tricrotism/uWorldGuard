package com.tricrotism.uworldguard.integration;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.wgcompat.SessionBridge;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.NullMarked;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes swapping uWorldGuard in place safe, as Cork does it: disable the plugin, close its
 * classloader, load the new jar and enable that.
 *
 * <p>Two things outlive the old copy. Paper registers a {@code provides} name with
 * {@code putIfAbsent} and Cork only removes the plugin's own name, so "WorldGuard" keeps pointing at
 * the disabled copy. And every plugin using the API stays linked to the old copy's classes, its
 * flags in the old registry and its queries against an engine that no longer runs. Nothing can relink
 * a loaded plugin, so those are disabled before uWorldGuard goes down and reloaded once the new copy
 * is up.
 */
@NullMarked
public final class HotSwap {

    /**
     * The plugins to reload, handed from the old copy to the new one. Only a string crosses, so
     * neither copy's classloader is pinned.
     */
    private static final String RELOAD = "uworldguard.hotswap.reload";

    private HotSwap() {}

    /**
     * Points each name uWorldGuard provides back at {@code self} when it still names a disabled copy
     * of uWorldGuard.
     *
     * @return whether a disabled copy was found, meaning this enable is a hot swap
     */
    public static boolean reclaimProvidedNames(final Plugin self, final Logger log) {
        boolean swapped = false;
        for (final String name : self.getPluginMeta().getProvidedPlugins()) {
            final Plugin holder = Bukkit.getPluginManager().getPlugin(name);
            if (holder == null || !isStaleCopy(holder, self)) {
                continue;
            }
            swapped = true;
            try {
                lookupNames().put(name.toLowerCase(Locale.ENGLISH), self);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                log.log(Level.WARNING, "'" + name + "' still names the copy of uWorldGuard that was just"
                    + " unloaded, and this server's plugin manager could not be updated. Plugins looking"
                    + " up " + name + " by name will fail until a restart.", e);
            }
        }
        return swapped;
    }

    /**
     * A disabled plugin with uWorldGuard's name that is not this copy: the one a swap left behind.
     */
    public static boolean isStaleCopy(final Plugin holder, final Plugin self) {
        return holder != self && !holder.isEnabled() && holder.getName().equals(self.getName());
    }

    /**
     * Disables every plugin using uWorldGuard, dependents of dependents first, while uWorldGuard can
     * still release their flags and handlers, and records them for {@link #reloadDependents}. Skipped
     * while the server stops, since nothing comes back then.
     */
    public static void disableDependents(final Plugin self, final Logger log) {
        if (Bukkit.isStopping()) {
            return;
        }
        final List<Plugin> dependents = dependents(self);
        if (dependents.isEmpty()) {
            return;
        }
        final List<String> names = new ArrayList<>(dependents.size());
        for (final Plugin dependent : dependents) {
            names.add(dependent.getName());
        }
        log.info("Disabling " + String.join(", ", names) + " first: they use uWorldGuard and would"
            + " keep running against this copy after it unloads.");
        final PluginManager manager = Bukkit.getPluginManager();
        for (int i = dependents.size() - 1; i >= 0; i--) {
            manager.disablePlugin(dependents.get(i));
        }
        System.setProperty(RELOAD, String.join(",", names));
    }

    /**
     * Reloads, one tick after enable, the plugins the previous copy disabled. On a swap from a copy
     * that did not disable them, the ones declaring a dependency are still running on its classes,
     * so those are reloaded too. Reloading goes through Cork, the only tool that can load a plugin
     * into a running server, and without it they are left disabled and named in the log.
     */
    public static void reloadDependents(final Plugin self, final boolean swapped, final Logger log) {
        final List<String> names = new ArrayList<>();
        final String handed = System.getProperty(RELOAD);
        System.clearProperty(RELOAD);
        if (handed != null && !handed.isEmpty()) {
            names.addAll(List.of(handed.split(",")));
        }
        if (swapped) {
            for (final Plugin dependent : dependents(self)) {
                if (!names.contains(dependent.getName())) {
                    names.add(dependent.getName());
                }
            }
        }
        if (names.isEmpty()) {
            return;
        }
        final Plugin cork = Bukkit.getPluginManager().getPlugin("cork");
        if (cork == null || !cork.isEnabled()) {
            log.warning("uWorldGuard was swapped without Cork. Reload or restart to bring back the"
                + " plugins that use it: " + String.join(", ", names));
            return;
        }
        log.info("Reloading the plugins that use uWorldGuard through Cork: " + String.join(", ", names));
        Bukkit.getGlobalRegionScheduler().runDelayed(self, _ -> {
            for (final String name : names) {
                if (Bukkit.getPluginManager().getPlugin(name) != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cork reload " + name);
                }
            }
        }, 1L);
    }

    /**
     * Enabled plugins that use uWorldGuard, in load order: those that depend or soft-depend on it or
     * on WorldGuard, those owning flags or session handlers, and those depending on any of these.
     */
    private static List<Plugin> dependents(final Plugin self) {
        final Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        final Set<String> used = new HashSet<>();
        used.add(self.getName().toLowerCase(Locale.ROOT));
        for (final String provided : self.getPluginMeta().getProvidedPlugins()) {
            used.add(provided.toLowerCase(Locale.ROOT));
        }
        final List<Plugin> dependents = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final Plugin plugin : plugins) {
                if (plugin == self || !plugin.isEnabled() || dependents.contains(plugin)
                    || !(dependsOn(plugin, used) || ownsFlagsOrHandlers(plugin))) {
                    continue;
                }
                dependents.add(plugin);
                used.add(plugin.getName().toLowerCase(Locale.ROOT));
                grew = true;
            }
        }
        dependents.sort((a, b) -> Integer.compare(indexOf(plugins, a), indexOf(plugins, b)));
        return dependents;
    }

    private static boolean dependsOn(final Plugin plugin, final Set<String> names) {
        for (final String name : plugin.getPluginMeta().getPluginDependencies()) {
            if (names.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (final String name : plugin.getPluginMeta().getPluginSoftDependencies()) {
            if (names.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean ownsFlagsOrHandlers(final Plugin plugin) {
        final ClassLoader loader = plugin.getClass().getClassLoader();
        return !Flags.ownedBy(loader).isEmpty() || SessionBridge.INSTANCE.hasHandlersOwnedBy(loader);
    }

    private static int indexOf(final Plugin[] plugins, final Plugin plugin) {
        for (int i = 0; i < plugins.length; i++) {
            if (plugins[i] == plugin) {
                return i;
            }
        }
        return plugins.length;
    }

    /**
     * Paper's name-to-plugin table, which {@code getPlugin} reads. No API changes an entry.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Plugin> lookupNames() throws ReflectiveOperationException {
        final PluginManager bukkit = Bukkit.getPluginManager();
        final Object paper = field(bukkit.getClass(), "paperPluginManager").get(bukkit);
        final Object instances = field(paper.getClass(), "instanceManager").get(paper);
        return (Map<String, Plugin>) field(instances.getClass(), "lookupNames").get(instances);
    }

    private static Field field(final Class<?> owner, final String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // declared further up
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
