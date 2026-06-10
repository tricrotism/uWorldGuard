package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StringFlag extends Flag<String> {

    public StringFlag(final String name) {
        super(name);
    }

    @Override
    public String parse(final String input) {
        return input;
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
