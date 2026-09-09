package com.tricrotism.uworldguard.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Brings a config file on disk up to date with the copy packaged in the jar, adding only the keys it
 * is missing. Values already present are never touched, so an admin's edits always win.
 *
 * <p>Bukkit writes a default resource once and then leaves it alone forever, which is fine for
 * {@code config.yml} — every read there passes a fallback, so a missing key quietly uses its default.
 * It is not fine for {@code messages.yml}: a key that is absent reads back as {@code null}, which the
 * sender treats as "disabled", so a message added in an update would ship silently dead to every
 * existing install.
 *
 * <p>Comments come across with the key they document, and the file is only rewritten when something
 * was actually added — an already-current file keeps its modification time and formatting.
 */
@NullMarked
public final class ConfigUpdater {

    private ConfigUpdater() {}

    /**
     * Add to {@code file} any key the packaged {@code resource} has and it does not.
     *
     * @return the keys added, in the order the packaged file declares them; empty if it was already
     * current, or if either file could not be read
     */
    public static List<String> merge(final Plugin plugin, final File file, final String resource) {
        if (!file.exists()) {
            return List.of();
        }

        final YamlConfiguration packaged = load(plugin, resource);
        if (packaged == null) {
            return List.of();
        }

        final YamlConfiguration live = new YamlConfiguration();
        try {
            live.load(file);
        } catch (final IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING,
                "Could not read " + file.getName() + " to check it for new settings; leaving it alone", e);
            return List.of();
        }

        final List<String> added = new ArrayList<>();
        final List<String> newSections = new ArrayList<>();
        for (final String key : packaged.getKeys(true)) {
            if (packaged.isConfigurationSection(key)) {
                if (!live.contains(key)) {
                    newSections.add(key);
                }
                continue;
            }
            if (live.contains(key)) {
                continue;
            }
            live.set(key, packaged.get(key));
            live.setComments(key, packaged.getComments(key));
            live.setInlineComments(key, packaged.getInlineComments(key));
            added.add(key);
        }

        if (added.isEmpty()) {
            return List.of();
        }
        carrySectionComments(packaged, live, newSections);
        try {
            live.save(file);
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write new settings into " + file.getName(), e);
            return List.of();
        }
        return added;
    }

    /**
     * Copies the comments that document a section the file did not have, once its keys have been
     * added and the section therefore exists.
     *
     * <p>A section's own comments cannot be copied in the loop above, because that loop skips section
     * keys: setting one would write the packaged section wholesale and take out any sub-key the
     * release added underneath it. Skipping them entirely lost the comments instead, and a section
     * header is where the setting is usually explained. A new {@code logging:} block arrived on an
     * existing install as two bare lines, while a fresh install got the paragraph saying what it does.
     */
    private static void carrySectionComments(
        final YamlConfiguration packaged, final YamlConfiguration live, final List<String> newSections
    ) {
        for (final String section : newSections) {
            if (!live.isConfigurationSection(section)) {
                continue;
            }
            live.setComments(section, packaged.getComments(section));
            live.setInlineComments(section, packaged.getInlineComments(section));
        }
    }

    private static @org.jspecify.annotations.Nullable YamlConfiguration load(
        final Plugin plugin, final String resource
    ) {
        final InputStream stream = plugin.getResource(resource);
        if (stream == null) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read packaged " + resource, e);
            return null;
        }
    }
}
