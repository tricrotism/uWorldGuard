package com.tricrotism.uworldguard.text;

import org.jspecify.annotations.NullMarked;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy color codes, and the translation into MiniMessage that lets them keep working.
 *
 * <p>uWorldGuard renders everything through MiniMessage, so an operator who types {@code &aWelcome}
 * into a greeting flag or messages.yml would otherwise see the literal text {@code &aWelcome}. Years
 * of WorldGuard, and every color-code chart on the internet, say that should be green. So anything
 * carrying a legacy code is translated first and parsed as MiniMessage after.
 *
 * <p>Translation is textual rather than a deserialize-then-reserialize round trip, and that is the
 * whole point: a round trip through {@code MiniMessage.serialize} escapes {@code <} and would turn a
 * template's own {@code <player>} placeholder into literal text. Rewriting only the codes leaves
 * every MiniMessage tag alone, so a half-converted line like {@code &aWelcome, <player>!} keeps both
 * the color and the placeholder.
 *
 * <p>Colors emit a {@code <reset>} before the tag because that is what legacy means: a color code
 * clears bold and italic, where a MiniMessage color tag leaves them running.
 */
@NullMarked
public final class LegacyText {

    private static final Pattern CODE = Pattern.compile("[&§](?:[0-9a-fk-orA-FK-OR]|#[0-9a-fA-F]{6})");

    private LegacyText() {}

    /**
     * Whether {@code text} carries at least one legacy color or format code. A bare {@code &} is not
     * one, so prose like "Bed &amp; Breakfast" stays MiniMessage.
     */
    public static boolean isLegacy(final String text) {
        return CODE.matcher(text).find();
    }

    /**
     * {@code text} with its legacy codes rewritten as MiniMessage tags. Everything else, MiniMessage
     * tags and placeholders included, is copied through untouched.
     */
    public static String toMiniMessage(final String text) {
        final Matcher matcher = CODE.matcher(text);
        final StringBuilder out = new StringBuilder(text.length() + 32);
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            out.append(tagFor(text.substring(matcher.start() + 1, matcher.end())));
            last = matcher.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    private static String tagFor(final String code) {
        if (code.charAt(0) == '#') {
            return "<reset><" + code + ">";
        }
        return switch (Character.toLowerCase(code.charAt(0))) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }
}
