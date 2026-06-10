package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

@NullMarked
public final class BooleanFlag extends Flag<Boolean> {

    public BooleanFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Boolean parse(final String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> Boolean.TRUE;
            case "false", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Override
    public @Nullable Boolean unmarshal(final Object stored) {
        if (stored instanceof Boolean b) {
            return b;
        }
        return parse(String.valueOf(stored));
    }

    @Override
    public Object marshal(final Boolean value) {
        return value;
    }
}
