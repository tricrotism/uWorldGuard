package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NullMarked
public final class StringFlag extends Flag<String> {

    private static final Pattern ESCAPED_NEWLINE = Pattern.compile("\\\\(\\\\?)n");

    public StringFlag(final String name) {
        super(name);
    }

    /**
     * A typed {@code \n} becomes a line break, as WorldGuard does, and {@code \\n} keeps a literal
     * {@code \n}.
     */
    @Override
    public String parse(final String input) {
        return unescape(input);
    }

    public static String unescape(final String input) {
        return ESCAPED_NEWLINE.matcher(input)
            .replaceAll(match -> match.group(1).isEmpty() ? "\n" : Matcher.quoteReplacement("\\n"));
    }

    /**
     * The inverse of {@link #parse}, so a value offered for editing reads back as itself.
     */
    @Override
    public String toInput(final String value) {
        return value.replace("\\n", "\\\\n").replace("\n", "\\n");
    }

    @Override
    public String unmarshal(final Object stored) {
        return String.valueOf(stored);
    }

    @Override
    public Object marshal(final String value) {
        return value;
    }
}
