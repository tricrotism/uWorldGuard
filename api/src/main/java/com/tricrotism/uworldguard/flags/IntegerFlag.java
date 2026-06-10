package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class IntegerFlag extends Flag<Integer> {

    public IntegerFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Integer parse(final String input) {
        try {
            return Integer.parseInt(input.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    @Override
    public @Nullable Integer unmarshal(final Object stored) {
        if (stored instanceof Number n) {
            return n.intValue();
        }
        return parse(String.valueOf(stored));
    }

    @Override
    public Object marshal(final Integer value) {
        return value;
    }
}
