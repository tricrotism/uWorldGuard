package com.tricrotism.uworldguard.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.NullMarked;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached MiniMessage formatting in both directions. Parsing or rendering the same template
 * repeatedly (deny messages, greetings, menu lore) is wasteful since {@link Component} is
 * immutable and shareable, so constant inputs are memoised:
 *
 * <ul>
 *   <li>{@link #format(String)} — deserialize (String → Component), keyed by the template.</li>
 *   <li>{@link #serialize(Component)} — serialize (Component → String), keyed by the component
 *       (Adventure components are immutable with value equality, so they are safe cache keys).</li>
 * </ul>
 *
 * <p>The resolver overload is not cached: its result depends on the per-call placeholders.
 *
 * <p>Plain maps rather than an evicting cache. A size-bounded cache has to know which entry to evict
 * next, so it records every <em>read</em> into a buffer and drains that buffer on a pool thread —
 * a cross-thread wakeup to serve a hit. These are read on deny messages, greetings, command replies
 * and menu lore, so that bookkeeping outweighs the parse it saves. A map read is a map read.
 *
 * <p>Bounding is still needed, because operator-typed flag values and strings from consumer plugins
 * both land here as keys. {@link #store} drops the whole map when it fills rather than evicting one
 * entry, which is the version of "bounded" that costs nothing on the read path. The working set is
 * the handful of templates the config defines, so a clear re-warms within a few calls.
 *
 * <p>Size alone never releases a one-off that arrived below the cap, so {@link #expire} also drops
 * everything on a timer. Expiring on a schedule rather than per entry keeps the read path free of
 * clock reads.
 */
@NullMarked
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /**
     * Enough for every template a configuration realistically defines, small enough that a
     * pathological caller filling it with one-off strings costs a re-warm rather than a heap.
     */
    private static final int MAX_ENTRIES = 1024;
    /**
     * How often {@link #expire} runs. Long enough that the config's templates stay warm between
     * sweeps, short enough that a burst of one-off strings does not sit in memory for the session.
     */
    public static final long EXPIRE_MINUTES = 10L;
    private static final Map<String, Component> DESERIALIZED = new ConcurrentHashMap<>();
    private static final Map<Component, String> SERIALIZED = new ConcurrentHashMap<>();

    private Messages() {
    }

    /**
     * Deserialize a constant or repeated template, memoised by the string itself.
     */
    public static Component format(final String template) {
        final Component cached = DESERIALIZED.get(template);
        if (cached != null) {
            return cached;
        }
        final Component parsed = MM.deserialize(normalise(template));
        store(DESERIALIZED, template, parsed);
        return parsed;
    }

    /**
     * Drops every memoised entry. Safe from any thread: a concurrent reader either sees the entry or
     * re-parses it.
     */
    public static void expire() {
        DESERIALIZED.clear();
        SERIALIZED.clear();
    }

    /**
     * Get-then-put rather than {@code computeIfAbsent}: the mapping function here is a MiniMessage
     * parse, and running it inside the map would hold a bin lock for its duration. Two threads
     * racing the same new template both parse it and one overwrites the other, which costs one
     * redundant parse and yields an equal, immutable result either way.
     */
    private static <K, V> void store(final Map<K, V> cache, final K key, final V value) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, value);
    }

    /**
     * Deserialize with per-call placeholders. Not cached, since the resolvers vary.
     */
    public static Component format(final String template, final TagResolver... resolvers) {
        return MM.deserialize(normalise(template), resolvers);
    }

    /**
     * A template as MiniMessage, translating legacy colour codes first if it turns out to use them.
     *
     * <p>Every string the plugin sends passes through here, which is what makes the two notations
     * interchangeable everywhere at once: message files, flag values an operator typed into chat, and
     * anything a consumer plugin hands us. Templates without a legacy code, which is all of
     * uWorldGuard's own, are returned unchanged and pay one failed regex scan.
     */
    private static String normalise(final String template) {
        return LegacyText.isLegacy(template) ? LegacyText.toMiniMessage(template) : template;
    }

    /**
     * Serialize a component back to its MiniMessage string, memoised by the component.
     */
    public static String serialize(final Component component) {
        final String cached = SERIALIZED.get(component);
        if (cached != null) {
            return cached;
        }
        final String text = MM.serialize(component);
        store(SERIALIZED, component, text);
        return text;
    }
}
