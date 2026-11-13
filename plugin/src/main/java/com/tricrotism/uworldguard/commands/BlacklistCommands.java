package com.tricrotism.uworldguard.commands;

import com.tricrotism.uworldguard.blacklist.Blacklist;
import com.tricrotism.uworldguard.blacklist.BlacklistService;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Edits the blacklist in {@code config.yml} from in game. A scope is {@code global} for the entries
 * every world shares, or a world name for that world's own.
 *
 * <p>Edits are serialized, since commands from players in different regions run on different threads
 * and the config object is not thread-safe. The file is written off the calling thread.
 */
@NullMarked
public final class BlacklistCommands {

    private static final String GLOBAL = "global";
    private static final List<String> OTHER_OPTIONS = List.of("items", "message", "comment", "ignore-perms", "ignore-groups");

    private final Plugin plugin;
    private final BlacklistService blacklist;
    private final Object editLock = new Object();
    private final Object writeLock = new Object();
    private long edits;
    private long written;

    public BlacklistCommands(final Plugin plugin, final BlacklistService blacklist) {
        this.plugin = plugin;
        this.blacklist = blacklist;
    }

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg blacklist list [scope]")
    @CommandDescription("List the blacklist entries for every world (global) or one world")
    @Permission("uworldguard.blacklist")
    public void list(
        final Source sender,
        @Argument(value = "scope", suggestions = "blacklist-scopes") final @Nullable String scopeArg
    ) {
        final String scope = scopeArg == null ? GLOBAL : scopeArg;
        final String path = path(sender, scope);
        if (path == null) return;

        final List<Map<String, Object>> entries;
        final boolean whitelist;
        synchronized (editLock) {
            final FileConfiguration config = plugin.getConfig();
            entries = entries(config, path);
            whitelist = config.getBoolean(path + ".use-as-whitelist", config.getBoolean("blacklist.use-as-whitelist", false));
        }
        send(sender, "<gray>Blacklist <aqua><scope></aqua>, <mode>. <count> entr<plural>.",
            Placeholder.unparsed("scope", scope),
            Placeholder.unparsed("mode", whitelist ? "whitelist mode" : "blacklist mode"),
            Placeholder.unparsed("count", Integer.toString(entries.size())),
            Placeholder.unparsed("plural", entries.size() == 1 ? "y" : "ies"));
        if (!GLOBAL.equalsIgnoreCase(scope)) {
            send(sender, "<gray>Global entries apply here too. See them with <aqua>/uwg blacklist list global</aqua>.");
        }
        for (int i = 0; i < entries.size(); i++) {
            final Map<String, Object> entry = entries.get(i);
            send(sender, "<white>#<number></white> <aqua><items>",
                Placeholder.unparsed("number", Integer.toString(i + 1)),
                Placeholder.unparsed("items", String.join(", ", Blacklist.strings(entry.get("items")))));
            for (final Map.Entry<String, Object> option : entry.entrySet()) {
                if (!"items".equals(option.getKey())) {
                    send(sender, "<gray>  <option>: <white><value>",
                        Placeholder.unparsed("option", option.getKey()),
                        Placeholder.unparsed("value", String.join(", ", Blacklist.strings(option.getValue()))));
                }
            }
        }
    }

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg blacklist add <scope> <items>")
    @CommandDescription("Add a blacklist entry for some items, comma-separated")
    @Permission("uworldguard.blacklist")
    public void add(
        final Source sender,
        @Argument(value = "scope", suggestions = "blacklist-scopes") final String scope,
        @Argument(value = "items", suggestions = "blacklist-items") @Greedy final String items
    ) {
        final List<String> names = materials(sender, items);
        if (names == null) return;
        edit(sender, scope, entries -> {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("items", names);
            entries.add(entry);
            return "Added entry #" + entries.size() + ". Give it rules with <aqua>/uwg blacklist set "
                + scope + " " + entries.size() + " on-place deny,tell</aqua>.";
        });
    }

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg blacklist remove <scope> <number>")
    @CommandDescription("Remove a blacklist entry")
    @Permission("uworldguard.blacklist")
    public void remove(
        final Source sender,
        @Argument(value = "scope", suggestions = "blacklist-scopes") final String scope,
        @Argument("number") final int number
    ) {
        edit(sender, scope, entries -> {
            if (number < 1 || number > entries.size()) {
                return null;
            }
            entries.remove(number - 1);
            return "Removed entry #" + number + ".";
        });
    }

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg blacklist set <scope> <number> <option> [value]")
    @CommandDescription("Set or clear one option of a blacklist entry (on-place, message, ignore-perms, ...)")
    @Permission("uworldguard.blacklist")
    public void set(
        final Source sender,
        @Argument(value = "scope", suggestions = "blacklist-scopes") final String scope,
        @Argument("number") final int number,
        @Argument(value = "option", suggestions = "blacklist-options") final String optionArg,
        @Argument(value = "value", suggestions = "blacklist-values") @Greedy final @Nullable String value
    ) {
        final String option = optionArg.toLowerCase(Locale.ROOT);
        final Blacklist.Rule rule = Blacklist.Rule.byKey(option);
        if (rule == null && !OTHER_OPTIONS.contains(option)) {
            error(sender, "Unknown option <aqua><option></aqua>. Use an on-... rule, message, comment,"
                + " ignore-perms, ignore-groups or items.", Placeholder.unparsed("option", option));
            return;
        }
        final Object stored;
        if (value == null || value.isBlank()) {
            if ("items".equals(option)) {
                error(sender, "An entry needs items. Remove the entry instead.");
                return;
            }
            stored = null;
        } else if ("items".equals(option)) {
            stored = materials(sender, value);
            if (stored == null) return;
        } else if (rule != null) {
            final List<String> actions = Blacklist.strings(value);
            for (final String action : actions) {
                if (Blacklist.Action.byName(action) == null) {
                    error(sender, "Unknown action <aqua><action></aqua>. Use allow, deny, notify, log, tell,"
                        + " kick or ban.", Placeholder.unparsed("action", action));
                    return;
                }
            }
            stored = actions;
        } else if (option.startsWith("ignore-")) {
            stored = Blacklist.strings(value);
        } else {
            stored = value;
        }
        edit(sender, scope, entries -> {
            if (number < 1 || number > entries.size()) {
                return null;
            }
            final Map<String, Object> entry = entries.get(number - 1);
            if (stored == null) {
                entry.remove(option);
                return "Cleared " + option + " on entry #" + number + ".";
            }
            entry.put(option, stored);
            return "Set " + option + " on entry #" + number + ".";
        });
    }

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg blacklist whitelist <scope> <enabled>")
    @CommandDescription("Turn whitelist mode on or off: listed items refuse every rule not set to allow")
    @Permission("uworldguard.blacklist")
    public void whitelist(
        final Source sender,
        @Argument(value = "scope", suggestions = "blacklist-scopes") final String scope,
        @Argument("enabled") final boolean enabled
    ) {
        final String path = path(sender, scope);
        if (path == null) return;
        final String key = path + ".use-as-whitelist";
        synchronized (editLock) {
            // A null default reads what config.yml holds, never the jar default
            if (!Boolean.valueOf(enabled).equals(plugin.getConfig().get(key, null))) {
                apply(key, enabled);
            }
        }
        success(sender, "Whitelist mode is <mode> for <aqua><scope></aqua>.",
            Placeholder.unparsed("mode", enabled ? "on" : "off"), Placeholder.unparsed("scope", scope));
    }

    @Suggestions("blacklist-scopes")
    public List<String> suggestScopes(final CommandContext<Source> context, final String input) {
        final List<String> scopes = new ArrayList<>();
        scopes.add(GLOBAL);
        for (final World world : Bukkit.getWorlds()) {
            scopes.add(world.getName());
        }
        return scopes;
    }

    @Suggestions("blacklist-options")
    public List<String> suggestOptions(final CommandContext<Source> context, final String input) {
        final List<String> options = new ArrayList<>();
        for (final Blacklist.Rule rule : Blacklist.Rule.values()) {
            options.add(rule.key());
        }
        options.addAll(OTHER_OPTIONS);
        return options;
    }

    /**
     * Completes the last name of a comma-separated list, keeping what is typed before it.
     */
    @Suggestions("blacklist-items")
    public List<String> suggestItems(final CommandContext<Source> context, final String input) {
        final int comma = input.lastIndexOf(',');
        final String done = input.substring(0, comma + 1);
        final String partial = input.substring(comma + 1).toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final Material material : Material.values()) {
            final String name = material.getKey().getKey();
            if (!material.isLegacy() && name.startsWith(partial)) {
                out.add(done + name);
            }
        }
        return out;
    }

    @Suggestions("blacklist-values")
    public List<String> suggestValues(final CommandContext<Source> context, final String input) {
        final String option = context.<String>optional("option").orElse("");
        if (option.equalsIgnoreCase("items")) {
            return suggestItems(context, input);
        }
        if (Blacklist.Rule.byKey(option) == null) {
            return List.of();
        }
        final int comma = input.lastIndexOf(',');
        final String done = input.substring(0, comma + 1);
        final List<String> out = new ArrayList<>();
        for (final Blacklist.Action action : Blacklist.Action.values()) {
            out.add(done + action.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * {@code change} returns the line to report, or {@code null} when the entry does not exist, which
     * leaves the config untouched. A change that leaves the entries as they were is reported but not
     * written.
     */
    private void edit(
        final Source sender, final String scope, final Function<List<Map<String, Object>>, @Nullable String> change
    ) {
        final String path = path(sender, scope);
        if (path == null) return;
        final String result;
        synchronized (editLock) {
            final FileConfiguration config = plugin.getConfig();
            final List<Map<String, Object>> entries = entries(config, path);
            final List<Map<String, Object>> before = entries(config, path);
            result = change.apply(entries);
            if (result != null && !entries.equals(before)) {
                apply(path + ".entries", entries);
            }
        }
        if (result == null) {
            error(sender, "No such entry. See them with <aqua>/uwg blacklist list <scope></aqua>.",
                Placeholder.unparsed("scope", scope));
            return;
        }
        success(sender, result);
    }

    private void apply(final String key, final Object value) {
        final String text;
        final long edit;
        synchronized (editLock) {
            final FileConfiguration config = plugin.getConfig();
            config.set(key, value);
            blacklist.load(config, plugin.getLogger());
            text = config.saveToString();
            edit = ++edits;
        }
        final Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            synchronized (writeLock) {
                if (edit < written) {
                    return;
                }
                written = edit;
                try {
                    final Path temp = file.resolveSibling("config.yml.tmp");
                    Files.writeString(temp, text, StandardCharsets.UTF_8);
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save the blacklist to config.yml", e);
                }
            }
        });
    }

    /**
     * The config path for a scope, or {@code null} after telling the sender it is not one.
     */
    private static @Nullable String path(final Source sender, final String scope) {
        if (GLOBAL.equalsIgnoreCase(scope)) {
            return "blacklist";
        }
        if (Bukkit.getWorld(scope) == null) {
            error(sender, "No world named <aqua><scope></aqua>. Use <aqua>global</aqua> or a world name.",
                Placeholder.unparsed("scope", scope));
            return null;
        }
        return "worlds." + scope + ".blacklist";
    }

    /**
     * A mutable copy of the scope's entries, with string keys so they can be edited and written back.
     */
    private static List<Map<String, Object>> entries(final FileConfiguration config, final String path) {
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final Map<?, ?> raw : config.getMapList(path + ".entries")) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> option : raw.entrySet()) {
                entry.put(String.valueOf(option.getKey()), option.getValue());
            }
            entries.add(entry);
        }
        return entries;
    }

    /**
     * The comma-separated names as material keys, or {@code null} after naming the ones that are not.
     */
    private static @Nullable List<String> materials(final Source sender, final String input) {
        final List<String> names = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        for (final String name : Blacklist.strings(input)) {
            final Material material = Material.matchMaterial(name);
            if (material == null) {
                unknown.add(name);
            } else {
                names.add(material.getKey().getKey());
            }
        }
        if (!unknown.isEmpty() || names.isEmpty()) {
            error(sender, "Unknown item: <aqua><names></aqua>.",
                Placeholder.unparsed("names", unknown.isEmpty() ? input : String.join(", ", unknown)));
            return null;
        }
        return names;
    }

    private static void send(final Source sender, final String message, final TagResolver... resolvers) {
        sender.source().sendMessage(Messages.format(message, resolvers));
    }

    private static void error(final Source sender, final String message, final TagResolver... resolvers) {
        send(sender, "<red>" + message, resolvers);
    }

    private static void success(final Source sender, final String message, final TagResolver... resolvers) {
        send(sender, "<green>" + message, resolvers);
    }
}
