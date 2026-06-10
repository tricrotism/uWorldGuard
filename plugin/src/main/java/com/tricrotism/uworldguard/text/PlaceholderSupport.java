package com.tricrotism.uworldguard.text;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

/**
 * Thin bridge to PlaceholderAPI. The referenced PlaceholderAPI classes resolve lazily on
 * first call, so this is only ever touched when {@link MessageService} has confirmed the
 * plugin is installed — keeping the soft dependency truly optional.
 */
@NullMarked final class PlaceholderSupport {

    private PlaceholderSupport() {
    }

    static String expand(final Player player, final String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
