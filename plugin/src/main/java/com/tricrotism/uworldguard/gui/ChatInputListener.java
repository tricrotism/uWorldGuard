package com.tricrotism.uworldguard.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.util.function.Consumer;

/**
 * Captures the next chat message from a player the GUI is awaiting input from. Chat fires async, so
 * the callback (which touches Bukkit state and reopens a menu) is dispatched to the player's entity
 * scheduler. Typing {@code cancel} aborts without invoking the callback.
 */
@NullMarked
public final class ChatInputListener implements Listener {

    private final Plugin plugin;
    private final ChatInputService service;

    public ChatInputListener(final Plugin plugin, final ChatInputService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Consumer<String> callback = service.take(player.getUniqueId());
        if (callback == null) return;

        event.setCancelled(true);
        final String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        player.getScheduler().run(plugin, _ -> {
            if (!message.equalsIgnoreCase("cancel")) {
                callback.accept(message);
            }
        }, null);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.cancel(event.getPlayer().getUniqueId());
    }
}
