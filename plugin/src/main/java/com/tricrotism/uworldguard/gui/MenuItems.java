package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;

/**
 * Shared static GUI items.
 */
@NullMarked final class MenuItems {

    static final String DEFINE = "uworldguard.region.define";
    static final String REMOVE = "uworldguard.region.remove";
    static final String FLAG = "uworldguard.region.flag";
    static final String MEMBERS = "uworldguard.region.members";

    private MenuItems() {
    }

    /**
     * Whether {@code player} may not do this, telling them so when they may not.
     *
     * <p>Opening the browser is one node, {@code uworldguard.menu}, but the things it does from there
     * are four, each with its own node on the command that does the same thing. Without this check,
     * granting someone the menu granted them define, remove, flag and members with it.
     */
    static boolean denied(final Player player, final String node) {
        if (player.hasPermission(node)) {
            return false;
        }
        player.sendMessage(Messages.format("<red>You don't have permission to do that."));
        return true;
    }

    static Item close() {
        return Item.builder()
            .setItemProvider(new ItemBuilder(Material.BARRIER).setName(Messages.format("<!i><red>Close")))
            .addClickHandler((item, click) -> click.player().closeInventory())
            .build();
    }
}
