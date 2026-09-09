// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.bukkit;

import com.sk89q.worldguard.LocalPlayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;

/**
 * The plugin object consumers reach through {@code WorldGuardPlugin.inst()}. The constructor records
 * {@code this}, so once {@code com.tricrotism.uworldguard.UWorldGuard} extends this class,
 * {@code inst()} answers with the running uWorldGuard instance; until then it answers {@code null}.
 *
 * <h2>Class-load contract</h2>
 * uWorldGuard's main class extends this, which means <em>this class loads on every server</em>,
 * including one with no WorldEdit installed. Therefore:
 *
 * <ul>
 *   <li>the superclass chain is {@link org.bukkit.plugin.java.JavaPlugin} only, and no interface
 *       here is WorldEdit-linked;</li>
 *   <li>no field is typed as a WorldEdit-linked class — {@link LocalPlayer} counts, because its
 *       supertype is a WorldEdit interface;</li>
 *   <li>no initializer touches such a type;</li>
 *   <li>every wrapping method constructs through
 *       {@code com.tricrotism.uworldguard.wgcompat.PlayerWrapping}, which is declared to return
 *       {@link Object}, and casts the result. The verifier never has to prove a WorldEdit subtype
 *       relationship, and {@code PlayerWrapping} loads only when a consumer actually calls one of
 *       these methods.</li>
 * </ul>
 *
 * <p>{@code getConfigManager()}, {@code getPlayerMoveListener()}, {@code createProtectionQuery()},
 * {@code getWorldEdit()}, {@code checkPermission(...)} and {@code onCommand(...)} are not shipped;
 * each returns or throws a type this layer does not provide.
 * {@code WorldGuard.getInstance().getPlatform().getGlobalStateManager()} replaces the first.
 */
public class WorldGuardPlugin extends org.bukkit.plugin.java.JavaPlugin {

    private static WorldGuardPlugin instance;

    public WorldGuardPlugin() {
        instance = this;
    }

    public static WorldGuardPlugin inst() {
        return instance;
    }

    public LocalPlayer wrapPlayer(final org.bukkit.entity.Player player) {
        return (LocalPlayer) com.tricrotism.uworldguard.wgcompat.PlayerWrapping.wrap(player);
    }

    /**
     * {@code silenced} is ignored: the wrapper never prints on its own.
     */
    public LocalPlayer wrapPlayer(final org.bukkit.entity.Player player, final boolean silenced) {
        return (LocalPlayer) com.tricrotism.uworldguard.wgcompat.PlayerWrapping.wrap(player);
    }

    /**
     * An offline player answers identity and permission questions only; WorldEdit's surface on it
     * throws {@link UnsupportedOperationException}.
     */
    public LocalPlayer wrapOfflinePlayer(final org.bukkit.OfflinePlayer player) {
        return (LocalPlayer) com.tricrotism.uworldguard.wgcompat.PlayerWrapping.wrapOffline(player);
    }

    public com.sk89q.worldedit.extension.platform.Actor wrapCommandSender(
        final org.bukkit.command.CommandSender sender) {
        return (com.sk89q.worldedit.extension.platform.Actor)
            com.tricrotism.uworldguard.wgcompat.PlayerWrapping.wrapSender(sender);
    }

    public org.bukkit.command.CommandSender unwrapActor(
        final com.sk89q.worldedit.extension.platform.Actor sender) {
        return com.tricrotism.uworldguard.wgcompat.PlayerWrapping.unwrap(sender);
    }

    public boolean hasPermission(final org.bukkit.command.CommandSender sender, final String perm) {
        return sender.hasPermission(perm);
    }

    /**
     * Answered from the {@code group.<name>} permission node — see
     * {@link com.tricrotism.uworldguard.wgcompat.Groups}. False for a player who is offline.
     */
    public boolean inGroup(final org.bukkit.OfflinePlayer player, final String group) {
        return com.tricrotism.uworldguard.wgcompat.Groups.inGroup(player, group);
    }

    /**
     * @see #inGroup(org.bukkit.OfflinePlayer, String)
     */
    public String[] getGroups(final org.bukkit.OfflinePlayer player) {
        return com.tricrotism.uworldguard.wgcompat.Groups.of(player);
    }

    public boolean isFolia() {
        return FoliaCheck.PRESENT;
    }

    /**
     * Writes {@code defaultName} out of this plugin's jar to {@code actual} when it is missing.
     */
    public void createDefaultConfiguration(final File actual, final String defaultName) {
        if (actual.exists()) {
            return;
        }
        final File parent = actual.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream source = getResource(defaultName)) {
            if (source == null) {
                return;
            }
            try (OutputStream target = Files.newOutputStream(actual.toPath())) {
                source.transferTo(target);
            }
        } catch (final IOException e) {
            getLogger().log(Level.WARNING, "Could not write default configuration " + defaultName, e);
        }
    }

    /**
     * Answers a {@code plugin.yml} probe with a WorldGuard descriptor carrying the version this
     * plugin publishes, and every other name out of the jar unchanged.
     *
     * <p>uWorldGuard is a {@code paper-plugin.yml} plugin, so its jar holds no {@code plugin.yml}.
     * A consumer that version-checks WorldGuard by reading that entry instead of the plugin metadata
     * therefore gets a null stream. MythicMobs is one: {@code WorldGuardSupport} opens a
     * {@link java.util.Scanner} straight over the result, so the probe reaches uWorldGuard as a
     * {@link NullPointerException} out of the scanner and MythicMobs reports only that WorldGuard
     * support failed to enable. The descriptor below keeps that one field in step with
     * {@code compatibility.report-version}, which is the setting that governs every other version a
     * consumer can read.
     *
     * <p>Paper never parses this: {@code PluginFileType} takes {@code paper-plugin.yml} when both
     * are present, and here the entry does not exist in the jar at all.
     */
    @Override
    public InputStream getResource(final String filename) {
        final InputStream fromJar = super.getResource(filename);
        if (fromJar != null || !"plugin.yml".equals(filename)) {
            return fromJar;
        }
        final String descriptor = "name: WorldGuard\n"
            + "version: '" + getPluginMeta().getVersion() + "'\n"
            + "main: com.sk89q.worldguard.bukkit.WorldGuardPlugin\n"
            + "api-version: '1.13'\n";
        return new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Holder so the class-presence probe runs once, on first {@link #isFolia()} call.
     */
    private static final class FoliaCheck {

        static final boolean PRESENT = probe();

        private static boolean probe() {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return true;
            } catch (final ClassNotFoundException notFolia) {
                return false;
            }
        }
    }
}
