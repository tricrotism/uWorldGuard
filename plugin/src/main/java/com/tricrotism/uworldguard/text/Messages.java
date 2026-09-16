package com.tricrotism.uworldguard.text;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.NullMarked;

/**
 * Cached MiniMessage formatting in both directions. Parsing or rendering the same template
 * repeatedly (deny messages, greetings, menu lore) is wasteful since {@link Component} is
 * immutable and shareable, so constant inputs are memoised in bounded Caffeine caches:
 *
 * <ul>
 *   <li>{@link #format(String)} — deserialize (String → Component), keyed by the template.</li>
 *   <li>{@link #serialize(Component)} — serialize (Component → String), keyed by the component
 *       (Adventure components are immutable with value equality, so they are safe cache keys).</li>
 * </ul>
 *
 * <p>The resolver overload is not cached: its result depends on the per-call placeholders.
 */
@NullMarked
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Cache<String, Component> DESERIALIZED = Caffeine.newBuilder()
        .maximumSize(1024)
        .build();
    private static final Cache<Component, String> SERIALIZED = Caffeine.newBuilder()
        .maximumSize(1024)
        .build();

    private Messages() {
    }

    /**
     * Deserialize a constant or repeated template, memoised by the string itself.
     */
    public static Component format(final String template) {
        return DESERIALIZED.get(template, t -> MM.deserialize(normalise(t)));
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
        return SERIALIZED.get(component, MM::serialize);
    }
}
