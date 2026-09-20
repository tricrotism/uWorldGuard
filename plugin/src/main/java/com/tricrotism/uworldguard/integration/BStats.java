package com.tricrotism.uworldguard.integration;

import org.bstats.MetricsBase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * bStats wiring that reports uWorldGuard's real version. The stock {@code org.bstats.bukkit.Metrics}
 * reads {@code getDescription().getVersion()} on every submission, which {@link ReportedVersion} has
 * rewritten to the WorldGuard API level. Otherwise this mirrors the stock class, sharing its
 * {@code plugins/bStats/config.yml}.
 */
@NullMarked
public final class BStats {

    private BStats() {}

    public static MetricsBase start(final Plugin plugin, final int serviceId) {
        final File configFile = new File(new File(plugin.getDataFolder().getParentFile(), "bStats"), "config.yml");
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.isSet("serverUuid")) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.addDefault("logSentData", false);
            config.addDefault("logResponseStatusText", false);
            config.options().copyDefaults(true);
            try {
                config.save(configFile);
            } catch (final IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not write " + configFile, e);
            }
        }

        final String version = ReportedVersion.real(plugin);
        return new MetricsBase(
            "bukkit",
            config.getString("serverUuid"),
            serviceId,
            config.getBoolean("enabled", true),
            builder -> {
                builder.appendField("playerAmount", Bukkit.getOnlinePlayers().size());
                builder.appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
                builder.appendField("bukkitVersion", Bukkit.getVersion());
                builder.appendField("bukkitName", Bukkit.getName());
                builder.appendField("javaVersion", System.getProperty("java.version"));
                builder.appendField("osName", System.getProperty("os.name"));
                builder.appendField("osArch", System.getProperty("os.arch"));
                builder.appendField("osVersion", System.getProperty("os.version"));
                builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
            },
            builder -> builder.appendField("pluginVersion", version),
            task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task),
            plugin::isEnabled,
            (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
            message -> plugin.getLogger().info(message),
            config.getBoolean("logFailedRequests", false),
            config.getBoolean("logSentData", false),
            config.getBoolean("logResponseStatusText", false),
            false);
    }
}
