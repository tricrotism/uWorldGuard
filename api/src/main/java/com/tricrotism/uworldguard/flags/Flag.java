package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A typed region flag. {@code T} is the runtime value type stored on a region.
 * Subclasses define how the value round-trips to/from a storage-friendly object
 * (String, Number, Boolean) and how it parses from command input.
 */
@NullMarked
public abstract class Flag<T> {

    private final String name;
    private @Nullable FlagCategory category;

    protected Flag(final String name) {
        this.name = name;
    }

    public final String getName() {
        return name;
    }

    /**
     * The menu grouping this flag belongs to, or {@code null} if uncategorised.
     */
    public final @Nullable FlagCategory getCategory() {
        return category;
    }

    /**
     * Assigned once at registration.
     */
    final void setCategory(final FlagCategory category) {
        this.category = category;
    }

    /**
     * Parse a value from command-line input, or {@code null} if it cannot be parsed.
     */
    public abstract @Nullable T parse(String input);

    /**
     * Convert a stored object (from YAML/JSON/SQL) into a value, or {@code null} if invalid.
     */
    public abstract @Nullable T unmarshal(Object stored);

    /**
     * Convert a value into a storage-friendly object (String, Number, Boolean, List).
     */
    public abstract Object marshal(T value);

    @Override
    public final boolean equals(final Object o) {
        return o instanceof Flag<?> other && name.equals(other.name);
    }

    @Override
    public final int hashCode() {
        return name.hashCode();
    }

    @Override
    public final String toString() {
        return name;
    }
}
