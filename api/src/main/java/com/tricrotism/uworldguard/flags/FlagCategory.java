package com.tricrotism.uworldguard.flags;

import org.jspecify.annotations.NullMarked;

/**
 * Coarse grouping used to organise flags in menus and command output. Mirrors the
 * sections that built-in flags are declared under in {@link Flags}.
 */
@NullMarked
public enum FlagCategory {

    PROTECTION("Protection"),
    ENVIRONMENT("Environment"),
    MOBS("Mobs & Explosions"),
    MOVEMENT("Movement"),
    MESSAGES("Messages & Effects"),
    ITEMS("Items & Blocks"),
    ENTRY("Entry & Actions"),
    PLAYER("Player State");

    private final String displayName;

    FlagCategory(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
