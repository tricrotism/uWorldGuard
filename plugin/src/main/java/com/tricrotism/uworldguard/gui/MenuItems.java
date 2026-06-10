package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.text.Messages;
import org.bukkit.Material;
import org.jspecify.annotations.NullMarked;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;

/**
 * Shared static GUI items.
 */
@NullMarked final class MenuItems {

    private MenuItems() {
    }

    static Item close() {
        return Item.builder()
            .setItemProvider(new ItemBuilder(Material.BARRIER).setName(Messages.format("<!i><red>Close")))
            .addClickHandler((item, click) -> click.player().closeInventory())
            .build();
    }
}
