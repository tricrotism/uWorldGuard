package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * A flag whose value is {@link State#ALLOW} or {@link State#DENY}.
 */
@NullMarked
public final class StateFlag extends Flag<State> {

    private final boolean allowedByDefault;

    public StateFlag(final String name, final boolean allowedByDefault) {
        super(name);
        this.allowedByDefault = allowedByDefault;
    }

    /**
     * Default when no region sets this flag.
     */
    public State getDefault() {
        return allowedByDefault ? State.ALLOW : State.DENY;
    }

    @Override
    public @Nullable State parse(final String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "allow", "true", "yes" -> State.ALLOW;
            case "deny", "false", "no" -> State.DENY;
            default -> null;
        };
    }

    @Override
    public @Nullable State unmarshal(final Object stored) {
        return parse(String.valueOf(stored));
    }

    @Override
    public Object marshal(final State value) {
        return value == State.ALLOW ? "allow" : "deny";
    }
}
