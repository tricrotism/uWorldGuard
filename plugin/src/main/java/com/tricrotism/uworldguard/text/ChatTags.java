package com.tricrotism.uworldguard.text;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player chat prefix/suffix currently in effect from the chat-prefix / chat-suffix region flags.
 * Written on the player's region thread as they move and read from the async chat thread, so both
 * maps are concurrent. Absence of an entry means no override.
 */
@NullMarked
public final class ChatTags {

    private final Map<UUID, String> prefixes = new ConcurrentHashMap<>();
    private final Map<UUID, String> suffixes = new ConcurrentHashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deafened = ConcurrentHashMap.newKeySet();

    public void setPrefix(final UUID uuid, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            prefixes.remove(uuid);
        } else {
            prefixes.put(uuid, value);
        }
    }

    public void setSuffix(final UUID uuid, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            suffixes.remove(uuid);
        } else {
            suffixes.put(uuid, value);
        }
    }

    public @Nullable String prefix(final UUID uuid) {
        return prefixes.get(uuid);
    }

    public @Nullable String suffix(final UUID uuid) {
        return suffixes.get(uuid);
    }

    public void setMuted(final UUID uuid, final boolean value) {
        toggle(muted, uuid, value);
    }

    public void setDeafened(final UUID uuid, final boolean value) {
        toggle(deafened, uuid, value);
    }

    public boolean isMuted(final UUID uuid) {
        return !muted.isEmpty() && muted.contains(uuid);
    }

    public boolean isDeafened(final UUID uuid) {
        return !deafened.isEmpty() && deafened.contains(uuid);
    }

    /**
     * Whether anybody at all is muted or deafened, so the chat handler can return before touching
     * either set in the overwhelmingly common case where no region uses these flags.
     */
    public boolean anyChatRestrictions() {
        return !muted.isEmpty() || !deafened.isEmpty();
    }

    /**
     * Whether anybody is deafened, so a mute-only server never walks a message's recipients.
     */
    public boolean anyDeafened() {
        return !deafened.isEmpty();
    }

    private static void toggle(final Set<UUID> set, final UUID uuid, final boolean value) {
        if (value) {
            set.add(uuid);
        } else {
            set.remove(uuid);
        }
    }

    public void clear(final UUID uuid) {
        prefixes.remove(uuid);
        suffixes.remove(uuid);
        muted.remove(uuid);
        deafened.remove(uuid);
    }

    public boolean isEmpty() {
        return prefixes.isEmpty() && suffixes.isEmpty();
    }
}
