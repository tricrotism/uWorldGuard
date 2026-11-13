package com.tricrotism.uworldguard.blacklist;

import com.tricrotism.uworldguard.blacklist.Blacklist.Action;
import com.tricrotism.uworldguard.blacklist.Blacklist.Entry;
import com.tricrotism.uworldguard.blacklist.Blacklist.Rule;
import com.tricrotism.uworldguard.text.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds every world's {@link Blacklist} and carries out what its entries say: refuse, tell the
 * player, tell staff, log, kick or ban.
 *
 * <p>Configured in {@code config.yml}. Entries under {@code blacklist} apply in every world, and a
 * world's own {@code worlds.<name>.blacklist} entries add to them. A world can also set its own
 * {@code use-as-whitelist}.
 *
 * <p>Read from every region thread. The lists sit behind {@code volatile} references replaced whole
 * on reload, so a world with no list costs a map lookup and a null check.
 */
@NullMarked
public final class BlacklistService {

    public static final String NOTIFY_PERMISSION = "uworldguard.blacklist.notify";

    /**
     * WorldGuard's window: the same player repeating the same thing within it is not told, logged or
     * reported again. Refusing still happens every time.
     */
    private static final long REPEAT_WINDOW_MILLIS = 3000L;
    /**
     * Size at which expired repeat records are swept. Entries only live for the window, so the map
     * holds the causes of the last few seconds, and this bounds it against a dispenser farm.
     */
    private static final int REPEAT_SWEEP_SIZE = 1024;

    private record Repeat(Object cause, Rule rule) {}

    private final Plugin plugin;
    private final MessageService messages;
    private final BlacklistLog log;
    private final Map<Repeat, Long> lastReported = new ConcurrentHashMap<>();
    private volatile @Nullable Blacklist global;
    private volatile Map<String, Blacklist> byWorld = Map.of();
    private volatile boolean notifies;
    private volatile Set<UUID> staff = Set.of();

    public BlacklistService(final Plugin plugin, final MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.log = new BlacklistLog(plugin);
    }

    public void load(final FileConfiguration config, final Logger logger) {
        final boolean whitelist = config.getBoolean("blacklist.use-as-whitelist", false);
        final List<Entry> shared = Blacklist.parse(config.getMapList("blacklist.entries"), "blacklist.entries", logger);

        final Map<String, Blacklist> map = new HashMap<>();
        boolean anyNotify = notifies(shared);
        final ConfigurationSection worlds = config.getConfigurationSection("worlds");
        if (worlds != null) {
            for (final String world : worlds.getKeys(false)) {
                final ConfigurationSection section = worlds.getConfigurationSection(world + ".blacklist");
                if (section == null) {
                    continue;
                }
                final List<Entry> own = Blacklist.parse(section.getMapList("entries"),
                    "worlds." + world + ".blacklist.entries", logger);
                anyNotify |= notifies(own);
                final List<Entry> entries = new ArrayList<>(shared);
                entries.addAll(own);
                final Blacklist list = Blacklist.of(section.getBoolean("use-as-whitelist", whitelist), entries);
                if (list != null) {
                    map.put(world, list);
                }
            }
        }
        this.global = Blacklist.of(whitelist, shared);
        this.byWorld = Map.copyOf(map);
        this.notifies = anyNotify;
        log.configure(config.getBoolean("blacklist.logging.console", true),
            config.getBoolean("blacklist.logging.file", true));
        lastReported.clear();
    }

    public void start() {
        log.start();
    }

    /**
     * Sets the online holders of {@link #NOTIFY_PERMISSION} that staff reports go to. Until it is
     * set, nobody receives them.
     */
    public void useStaff(final Set<UUID> holders) {
        this.staff = holders;
    }

    /**
     * @return whether any loaded entry reports to staff, so their permission is worth tracking
     */
    public boolean notifies() {
        return notifies;
    }

    private static boolean notifies(final List<Entry> entries) {
        for (final Entry entry : entries) {
            for (final Rule rule : Rule.values()) {
                for (final Action action : entry.actions(rule)) {
                    if (action == Action.NOTIFY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void stop() {
        log.stop();
    }

    /**
     * The list that applies in {@code world}, or {@code null} when nothing is listed there.
     */
    public @Nullable Blacklist forWorld(final World world) {
        final Map<String, Blacklist> map = byWorld;
        if (map.isEmpty()) {
            return global;
        }
        final Blacklist own = map.get(world.getName());
        return own != null ? own : global;
    }

    /**
     * Runs {@code list} against one action and carries out what the matching entries say.
     *
     * @param player who did it, or {@code null} for a dispenser
     * @param silent decide without telling, logging or reporting anyone, as WorldGuard does for
     *               pickups, which the server retries every tick
     * @return whether the action may go ahead
     */
    public boolean allows(
        final Blacklist list, final Rule rule, final @Nullable Player player, final Material target,
        final World world, final int x, final int y, final int z, final boolean silent
    ) {
        final Entry[] entries = list.entriesFor(target);
        if (entries == null) {
            return true;
        }
        final Object cause = player != null ? player.getUniqueId() : world.getName() + " " + x + " " + y + " " + z;
        final boolean repeating = !silent && repeating(cause, rule);
        boolean allowed = true;
        for (final Entry entry : entries) {
            if ((player == null || !entry.ignores(player))
                && !entryAllows(list, entry, rule, player, target, world, x, y, z, silent, repeating)) {
                allowed = false;
            }
        }
        return allowed;
    }

    private boolean entryAllows(
        final Blacklist list, final Entry entry, final Rule rule, final @Nullable Player player,
        final Material target, final World world, final int x, final int y, final int z,
        final boolean silent, final boolean repeating
    ) {
        boolean result = !list.whitelist();
        for (final Action action : entry.actions(rule)) {
            switch (action) {
                case ALLOW -> {
                    if (silent) {
                        return true;
                    }
                    result = true;
                }
                case DENY -> {
                    if (silent) {
                        return false;
                    }
                    result = false;
                }
                case TELL -> {
                    if (!silent && !repeating && player != null) {
                        tell(player, entry, rule, target);
                    }
                }
                case NOTIFY -> {
                    if (!silent && !repeating) {
                        notifyStaff(who(player, x, y, z), entry, rule, target);
                    }
                }
                case LOG -> {
                    if (!silent && !repeating) {
                        log.write(world.getName(), who(player, x, y, z) + " tried to " + rule.description() + " "
                            + itemName(target) + " at " + x + ", " + y + ", " + z
                            + (entry.comment() != null ? " (" + entry.comment() + ")" : ""));
                    }
                }
                case KICK -> {
                    if (!silent && player != null) {
                        final Component reason = reason(player, entry, "blacklist-kick", rule,
                            Component.translatable(target.translationKey()));
                        player.getScheduler().run(plugin, task -> player.kick(reason), null);
                    }
                }
                case BAN -> {
                    if (!silent && player != null) {
                        final String reason = PlainTextComponentSerializer.plainText().serialize(
                            reason(player, entry, "blacklist-ban", rule, Component.text(itemName(target))));
                        player.getScheduler().run(plugin,
                            task -> player.ban(reason, (Instant) null, "uWorldGuard blacklist", true), null);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Whether {@code cause} did the same thing within the window. Records it when not.
     */
    private boolean repeating(final Object cause, final Rule rule) {
        final long now = System.currentTimeMillis();
        final Repeat key = new Repeat(cause, rule);
        final Long last = lastReported.get(key);
        if (last != null && now - last < REPEAT_WINDOW_MILLIS) {
            return true;
        }
        if (lastReported.size() >= REPEAT_SWEEP_SIZE) {
            lastReported.values().removeIf(time -> now - time >= REPEAT_WINDOW_MILLIS);
        }
        lastReported.put(key, now);
        return false;
    }

    private void tell(final Player player, final Entry entry, final Rule rule, final Material target) {
        final TagResolver[] resolvers = resolvers(rule, Component.translatable(target.translationKey()));
        final String custom = entry.message();
        if (custom != null) {
            player.sendMessage(messages.render(custom, player, resolvers));
        } else {
            messages.send(player, "blacklist-denied", resolvers);
        }
    }

    /**
     * The entry's own message, else the {@code key} entry in messages.yml, else a plain sentence,
     * since a kick needs some reason even when the message is turned off.
     */
    private Component reason(
        final Player player, final Entry entry, final String key, final Rule rule, final Component item
    ) {
        final String custom = entry.message();
        final String template = custom != null ? custom : messages.raw(key);
        if (template == null || template.isBlank() || "false".equalsIgnoreCase(template)) {
            return Component.text("You can't " + rule.description() + " ").append(item).append(Component.text("."));
        }
        return messages.render(template, player, resolvers(rule, item));
    }

    /**
     * Renders the report once and hops onto each tracked holder's own thread for the
     * {@link #NOTIFY_PERMISSION} test and the send. The test repeats there because the holder list
     * is up to a second old. Walking every online player instead cost one task per player per
     * report, and a dispenser farm reports once per dispenser per repeat window.
     */
    private void notifyStaff(final String who, final Entry entry, final Rule rule, final Material target) {
        final Set<UUID> holders = staff;
        if (holders.isEmpty()) {
            return;
        }
        final String comment = entry.comment();
        final Component line = messages.broadcast("blacklist-notify",
            Placeholder.unparsed("player", who),
            Placeholder.unparsed("action", rule.description()),
            Placeholder.component("item", Component.translatable(target.translationKey())),
            Placeholder.unparsed("comment", comment != null ? " (" + comment + ")" : ""));
        if (line == null) {
            return;
        }
        for (final UUID id : holders) {
            final Player recipient = Bukkit.getPlayer(id);
            if (recipient != null) {
                recipient.getScheduler().run(plugin, _ -> {
                    if (recipient.hasPermission(NOTIFY_PERMISSION)) {
                        recipient.sendMessage(line);
                    }
                }, null);
            }
        }
    }

    private static TagResolver[] resolvers(final Rule rule, final Component item) {
        return new TagResolver[]{
            Placeholder.unparsed("action", rule.description()),
            Placeholder.component("item", item)
        };
    }

    private static String who(final @Nullable Player player, final int x, final int y, final int z) {
        return player != null ? player.getName() : "A dispenser at " + x + ", " + y + ", " + z;
    }

    private static String itemName(final Material material) {
        return material.getKey().getKey().replace('_', ' ');
    }
}
