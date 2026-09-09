package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jspecify.annotations.NullMarked;
import xyz.xenondevs.inventoryaccess.component.AdventureComponentWrapper;
import xyz.xenondevs.inventoryaccess.component.ComponentWrapper;
import xyz.xenondevs.invui.item.Click;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Shared static GUI items, plus the two InvUI bridge helpers the menus are written against.
 *
 * <p>{@link #wrap} adapts an Adventure {@link Component} to the {@link ComponentWrapper} InvUI's
 * item and window builders take. {@link #clickable} builds an {@link Item} whose provider is
 * re-evaluated on every {@code notifyWindows()} and whose click handler receives both the item, for
 * repaints, and the {@link Click}. Items whose appearance cannot change hoist their provider into a
 * local so the supplier returns the same instance instead of rebuilding it per repaint.
 */
@NullMarked final class MenuItems {

    static final String DEFINE = "uworldguard.region.define";
    static final String REMOVE = "uworldguard.region.remove";
    static final String FLAG = "uworldguard.region.flag";
    static final String MEMBERS = "uworldguard.region.members";

    private MenuItems() {
    }

    static ComponentWrapper wrap(final Component component) {
        return new AdventureComponentWrapper(component);
    }

    static Item clickable(final Supplier<ItemProvider> provider, final BiConsumer<Item, Click> onClick) {
        return new AbstractItem() {
            @Override
            public ItemProvider getItemProvider() {
                return provider.get();
            }

            @Override
            public void handleClick(final ClickType clickType, final Player player, final InventoryClickEvent event) {
                onClick.accept(this, new Click(event));
            }
        };
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
        final ItemProvider provider = new ItemBuilder(Material.BARRIER)
            .setDisplayName(wrap(Messages.format("<!i><red>Close")));
        return clickable(() -> provider, (item, click) -> click.getPlayer().closeInventory());
    }
}
