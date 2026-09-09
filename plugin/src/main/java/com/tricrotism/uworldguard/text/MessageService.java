package com.tricrotism.uworldguard.text;

import com.tricrotism.uworldguard.config.ConfigUpdater;
import com.tricrotism.uworldguard.flags.Flag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Configurable, cooldown-throttled messages backed by {@code messages.yml}. Each entry can be
 * recoloured, given PlaceholderAPI placeholders, or disabled (empty / {@code false}). A per-player,
 * per-key cooldown (default 3s) suppresses spam from rapidly repeated denials.
 *
 * <p>Reloadable and editable at runtime ({@link #reload()}, {@link #setMessage}, {@link
 * #setCooldownSeconds}) so the settings GUI and {@code /uwg reload} can change messages live.
 * Thread-safe: the template and cooldown maps are concurrent.
 */
@NullMarked
public final class MessageService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String DENY_KEY = "no-permission";
    private static final String DENY_PREFIX = DENY_KEY + "-";
    private static final Function<UUID, Map<String, AtomicLong>> NEW_MAP = k -> new ConcurrentHashMap<>();
    private static final Function<Flag<?>, String> DENY_KEY_FOR = flag -> DENY_PREFIX + flag.getName();

    private final Plugin plugin;
    private final File file;
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, AtomicLong>> lastSent = new ConcurrentHashMap<>();
    private final Map<Flag<?>, String> denyKeys = new ConcurrentHashMap<>();
    private volatile long cooldownMillis;
    /**
     * Whether any {@code no-permission-<flag>} entry exists; lets {@link #sendDeny} skip building
     * a per-flag key when nobody has configured one.
     */
    private volatile boolean denyOverrides;
    private final boolean placeholderApi;

    public MessageService(final Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        final List<String> added = ConfigUpdater.merge(plugin, file, "messages.yml");
        if (!added.isEmpty()) {
            plugin.getLogger().info("messages.yml gained " + added.size()
                + " new message(s) from this version: " + String.join(", ", added));
        }
        this.placeholderApi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        load();
    }

    /**
     * Reads the file into the live map by overwriting and then pruning, never by clearing first.
     * Every region thread reads {@code templates} while {@code /uwg reload} writes it, and a clear
     * left a window in which every message on the server was missing — denials, greetings and all —
     * for the width of a YAML parse.
     */
    private void load() {
        final FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        this.cooldownMillis = Math.max(0L, cfg.getLong("cooldown-seconds", 3L)) * 1000L;
        final ConfigurationSection section = cfg.getConfigurationSection("messages");
        boolean overrides = false;
        final Set<String> present = section == null ? Set.of() : section.getKeys(false);
        for (final String key : present) {
            templates.put(key, section.getString(key, ""));
            overrides |= key.startsWith(DENY_PREFIX);
        }
        templates.keySet().retainAll(present);
        this.denyOverrides = overrides;
    }

    /**
     * Re-read messages.yml from disk.
     */
    public void reload() {
        load();
    }

    public Set<String> keys() {
        return new TreeSet<>(templates.keySet());
    }

    public @Nullable String raw(final String key) {
        return templates.get(key);
    }

    public long cooldownSeconds() {
        return cooldownMillis / 1000L;
    }

    public void setCooldownSeconds(final long seconds) {
        this.cooldownMillis = Math.max(0L, seconds) * 1000L;
        save();
    }

    public void setMessage(final String key, final String value) {
        templates.put(key, value);
        if (key.startsWith(DENY_PREFIX)) {
            this.denyOverrides = true;
        }
        save();
    }

    /**
     * Serialises writes to messages.yml. The file is rewritten whole from {@link #templates}, so two
     * overlapping saves cannot lose an edit — but they could interleave their writes and leave a torn
     * document, which this prevents.
     */
    private final Object saveLock = new Object();

    /**
     * Writes the current messages back over the file, editing the document that is already there
     * rather than composing a fresh one. Building a new {@link YamlConfiguration} meant the first
     * edit through the settings GUI erased the whole explanatory header and every commented
     * {@code no-permission-<flag>} example — the file's documentation only survived until someone
     * used the feature it documented.
     *
     * <p>Off-thread, because the callers are not: an edit made through the settings menu arrives as a
     * chat callback dispatched to the editor's entity scheduler, and re-reading plus rewriting the
     * document there would block a region tick on two disk operations per keystroke-confirmed edit.
     * {@code templates} is concurrent, so the async task can read it directly.
     */
    private void save() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            synchronized (saveLock) {
                writeFile();
            }
        });
    }

    private void writeFile() {
        final YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
        } catch (final IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING,
                "Could not re-read messages.yml before saving; comments in it will be lost", e);
        }
        cfg.set("cooldown-seconds", cooldownMillis / 1000L);
        for (final Map.Entry<String, String> entry : templates.entrySet()) {
            cfg.set("messages." + entry.getKey(), entry.getValue());
        }
        try {
            cfg.save(file);
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save messages.yml", e);
        }
    }

    /**
     * Deserialize a template, expanding PlaceholderAPI placeholders against {@code player} when the
     * plugin is present. No cooldown — for greetings/farewells that should always show.
     */
    public Component render(final String template, final @Nullable Player player, final TagResolver... resolvers) {
        if (resolvers.length == 0
            && (player == null || !placeholderApi || template.indexOf('%') < 0)) {
            return Messages.format(template);
        }
        final String text = placeholderApi && player != null ? PlaceholderSupport.expand(player, template) : template;
        return MM.deserialize(text, resolvers);
    }

    /**
     * Send a configurable message by key, honouring its cooldown. No-op when the entry is disabled
     * or the player is still on cooldown for that key.
     */
    public void send(final Player player, final String key, final TagResolver... resolvers) {
        dispatch(player, key, templates.get(key), resolvers);
    }

    /**
     * Render a keyed message once for broadcasting to several players, or {@code null} when the entry
     * is disabled. Unlike {@link #send} there is no recipient and no cooldown: the same line goes to
     * everyone who should see it, so it is built once rather than per receiver, and a throttle keyed
     * on one of them would silence the others.
     *
     * <p>No PlaceholderAPI expansion, because there is no one player to expand against — the subject
     * of the message is passed in as a resolver by the caller.
     */
    public @Nullable Component broadcast(final String key, final TagResolver... resolvers) {
        final String template = templates.get(key);
        if (template == null || silenced(template)) {
            return null;
        }
        return render(template, null, resolvers);
    }

    /**
     * Send the denial message for {@code flag}: the per-flag override {@code no-permission-<flag>}
     * when messages.yml defines one, else the shared {@code no-permission} entry. The two levels
     * disable independently — a per-flag entry of {@code false} silences just that flag while the
     * shared message keeps working elsewhere, and disabling {@code no-permission} silences every
     * flag that has no override of its own.
     *
     * <p>Every flag gets its own cooldown, whichever entry supplies the text.
     */
    public void sendDeny(final Player player, final Flag<?> flag) {
        sendDeny(player, flag, null);
    }

    /**
     * Same, but a region's own {@code deny-message} takes precedence over both levels when set.
     * {@code %what%} in it expands to what was refused, as WorldGuard does — so a migrated
     * "You can't %what% here." reads correctly rather than showing the placeholder verbatim.
     */
    public void sendDeny(final Player player, final Flag<?> flag, final @Nullable String regionMessage) {
        final String cooldownKey = denyKeys.computeIfAbsent(flag, DENY_KEY_FOR);
        final String override = denyOverrides ? templates.get(cooldownKey) : null;
        final String configured = override != null ? override : templates.get(DENY_KEY);

        if (regionMessage != null && !regionMessage.isBlank() && !silenced(configured)) {
            dispatch(player, cooldownKey, regionMessage.replace("%what%", whatOf(flag)));
            return;
        }
        dispatch(player, cooldownKey, configured);
    }

    private static boolean silenced(final @Nullable String template) {
        return template != null && (template.isBlank() || "false".equalsIgnoreCase(template));
    }

    /**
     * The phrase WorldGuard substitutes for {@code %what%}. Only the flags that can carry a
     * {@code deny-message} need an entry; anything else falls back to the flag's own name, which still
     * reads as a sentence ("You can't chest-access here").
     */
    private static String whatOf(final Flag<?> flag) {
        return switch (flag.getName()) {
            case "build" -> "build";
            case "block-break", "deny-block-break" -> "break that block";
            case "block-place", "deny-block-place" -> "place that block";
            case "interact", "use" -> "use that";
            case "chest-access" -> "open that";
            default -> flag.getName();
        };
    }

    /**
     * Send a region-supplied message ({@code custom}) if set, else the configurable {@code fallbackKey}.
     * Cooldown is keyed on {@code fallbackKey} so a custom per-region message still can't spam.
     */
    public void sendFlag(final Player player, final @Nullable String custom, final String fallbackKey,
                         final TagResolver... resolvers) {
        final String template = custom != null && !custom.isBlank() ? custom : templates.get(fallbackKey);
        dispatch(player, fallbackKey, template, resolvers);
    }

    private void dispatch(final Player player, final String cooldownKey, final @Nullable String template,
                          final TagResolver... resolvers) {
        if (template == null || template.isBlank() || "false".equalsIgnoreCase(template)) {
            return;
        }
        if (onCooldown(player.getUniqueId(), cooldownKey)) {
            return;
        }
        player.sendMessage(render(template, player, resolvers));
    }

    private boolean onCooldown(final UUID uuid, final String key) {
        if (cooldownMillis <= 0L) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final Map<String, AtomicLong> perPlayer = lastSent.computeIfAbsent(uuid, NEW_MAP);
        final AtomicLong slot = perPlayer.get(key);
        if (slot == null) {
            perPlayer.putIfAbsent(key, new AtomicLong(now));
            return false;
        }
        if (now - slot.get() < cooldownMillis) {
            return true;
        }
        slot.set(now);
        return false;
    }

    /**
     * Expand PlaceholderAPI placeholders against {@code player} when the plugin is present; returns
     * {@code text} unchanged otherwise. For non-message uses (commands, location/level flag values).
     */
    public String expand(final Player player, final String text) {
        return placeholderApi ? PlaceholderSupport.expand(player, text) : text;
    }

    /**
     * Drop a player's cooldown records (call on quit to avoid unbounded growth).
     */
    public void clear(final UUID uuid) {
        lastSent.remove(uuid);
    }
}
