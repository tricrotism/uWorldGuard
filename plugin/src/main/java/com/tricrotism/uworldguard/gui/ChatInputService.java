package com.tricrotism.uworldguard.gui;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks players the GUI is waiting on for a typed chat value. {@link ChatInputListener} consumes
 * the next chat message from such a player and feeds it back to the registered callback.
 */
@NullMarked
public final class ChatInputService {

    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public void await(final UUID player, final Consumer<String> callback) {
        pending.put(player, callback);
    }

    public @Nullable Consumer<String> take(final UUID player) {
        return pending.remove(player);
    }

    public void cancel(final UUID player) {
        pending.remove(player);
    }
}
