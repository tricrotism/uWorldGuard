package com.tricrotism.uworldguard.integration;

import com.tricrotism.uworldguard.util.WorldGuardApiLevel;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Rewrites the version uWorldGuard reports to other plugins, for the operator who needs a consumer
 * that gates its WorldGuard hook on a version number.
 *
 * <p>uWorldGuard answers to the name "WorldGuard" through {@code provides}, but Paper attaches no
 * version to a provided name. A consumer that reads it gets uWorldGuard's own, so a check like
 * BetonQuest's "WorldGuard 7.0.0 or above" fails against a 1.x plugin even though the API it wants
 * is fully present. PvPManager is worse than a declined hook: it falls through to a legacy hook whose
 * class it no longer ships, and reports only that a hook failed to load. So the API level is
 * published by default. There is one version field per plugin and no way to answer one caller
 * differently from another, so this is all-or-nothing; {@code none} turns it off.
 *
 * <p>Reflective by necessity: {@code getDescription()} and {@code getPluginMeta()} are final. Both
 * are rewritten because consumers read either one. A failure here is reported rather than swallowed:
 * the operator asked for a specific version to be published, and silently publishing a different one
 * would send them hunting through the wrong plugin.
 */
@NullMarked
public final class ReportedVersion {

    private static volatile String real = "";

    private ReportedVersion() {}

    /**
     * uWorldGuard's real version, whatever it publishes to other plugins. Anything uWorldGuard says
     * about itself uses this, so a published version never reaches a place where the operator is
     * being told about uWorldGuard. That covers its own log lines and its update check.
     */
    public static String real(final org.bukkit.plugin.Plugin plugin) {
        final String captured = real;
        return captured.isEmpty() ? plugin.getPluginMeta().getVersion() : captured;
    }

    /**
     * @param configured what {@code compatibility.report-version} holds: blank for the WorldGuard API
     *                   level this build implements, {@code none} to publish uWorldGuard's own
     *                   version, or a version to publish verbatim
     */
    @SuppressWarnings("deprecation")
    public static void publish(final JavaPlugin plugin, final String configured, final Logger log) {
        final String trimmed = configured.trim();
        if ("none".equalsIgnoreCase(trimmed)) {
            return;
        }
        final String version = trimmed.isEmpty() ? WorldGuardApiLevel.VERSION : trimmed;
        final String actual = plugin.getPluginMeta().getVersion();
        rewrite(plugin.getPluginMeta(), version);
        rewrite(plugin.getDescription(), version);
        if (!version.equals(plugin.getPluginMeta().getVersion())
            || !version.equals(plugin.getDescription().getVersion())) {
            log.warning("compatibility.report-version is set to '" + version + "', but this server's"
                + " plugin metadata could not be rewritten, plugins still see " + actual + "."
                + " Report this with your Paper version.");
            return;
        }
        real = actual;
        log.info("Reporting version " + version + " to other plugins (real version: " + actual
            + "). This is compatibility.report-version. /version and metrics report it too.");
    }

    /**
     * Sets every {@code String version} field declared anywhere in {@code holder}'s hierarchy.
     * Field-shaped rather than method-shaped so it survives Paper moving the accessor around; the
     * caller reads the value back rather than trusting the write.
     */
    private static void rewrite(final Object holder, final String version) {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (!"version".equals(field.getName()) || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(holder, version);
                } catch (final RuntimeException | ReflectiveOperationException e) {
                    return;
                }
            }
        }
    }
}
