package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class DoubleFlag extends Flag<Double> {

    public DoubleFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Double parse(final String input) {
        try {
            return Double.parseDouble(input);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    @Override
    public @Nullable Double unmarshal(final Object stored) {
        if (stored instanceof Number n) {
            return n.doubleValue();
        }
        return parse(String.valueOf(stored));
    }

    @Override
    public Object marshal(final Double value) {
        return value;
    }
}
