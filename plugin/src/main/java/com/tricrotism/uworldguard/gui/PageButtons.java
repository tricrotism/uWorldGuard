package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.text.Messages;
import org.bukkit.Material;
import org.jspecify.annotations.NullMarked;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.controlitem.PageItem;

/**
 * Previous/next page controls for an InvUI {@link PagedGui}. {@link PageItem} binds to its gui and
 * handles the page change on click; each button only renders when there is a page to move to.
 */
@NullMarked final class PageButtons {

    private PageButtons() {
    }

    static final class Previous extends PageItem {
        Previous() {
            super(false);
        }

        @Override
        public ItemProvider getItemProvider(final PagedGui<?> gui) {
            if (!gui.hasPreviousPage()) {
                return ItemProvider.EMPTY;
            }
            return new ItemBuilder(Material.ARROW)
                .setDisplayName(MenuItems.wrap(Messages.format("<yellow>Previous page")));
        }
    }

    static final class Next extends PageItem {
        Next() {
            super(true);
        }

        @Override
        public ItemProvider getItemProvider(final PagedGui<?> gui) {
            if (!gui.hasNextPage()) {
                return ItemProvider.EMPTY;
            }
            return new ItemBuilder(Material.ARROW)
                .setDisplayName(MenuItems.wrap(Messages.format("<yellow>Next page")));
        }
    }
}
