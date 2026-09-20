package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.Messages;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * Applies the chat-prefix / chat-suffix region flags by wrapping the player's rendered chat with the
 * prefix/suffix current at their location (cached on move by {@code MovementListener}). The flag
 * values are parsed as MiniMessage. This runs on the async chat thread, so it only touches the
 * concurrent {@link ChatTags} cache — never Bukkit API.
 */
@NullMarked
public final class ChatListener implements Listener {

    private final ChatTags chatTags;

    public ChatListener(final ChatTags chatTags) {
        this.chatTags = chatTags;
    }

    /**
     * Enforces send-chat / receive-chat ahead of the tag rendering below. Both are read from the
     * {@link ChatTags} cache rather than resolved here, because this runs on the async chat thread
     * where a region lookup would be a Bukkit call off the owning region thread.
     *
     * <p>A deafened viewer is dropped from the recipient set rather than the message being cancelled,
     * so one player standing in a quiet region does not silence the message for everyone else. The
     * recipient walk is skipped unless someone is actually deafened: Paper builds the viewer set on
     * first access, so even asking for it costs a pass over every online player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChatRestrictions(final AsyncChatEvent event) {
        if (!chatTags.anyChatRestrictions()) {
            return;
        }
        if (chatTags.isMuted(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (chatTags.anyDeafened()) {
            event.viewers().removeIf(viewer -> viewer instanceof Player player && chatTags.isDeafened(player.getUniqueId()));
        }
    }

    @SuppressWarnings("OverrideOnly")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        if (chatTags.isEmpty()) {
            return;
        }
        final UUID uuid = event.getPlayer().getUniqueId();
        final String prefix = chatTags.prefix(uuid);
        final String suffix = chatTags.suffix(uuid);
        if (prefix == null && suffix == null) {
            return;
        }

        final Component pre = prefix == null ? Component.empty() : Messages.format(prefix);
        final Component suf = suffix == null ? Component.empty() : Messages.format(suffix);
        final ChatRenderer previous = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) ->
            pre.append(previous.render(source, sourceDisplayName, message, viewer)).append(suf));
    }
}
