package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;
import xyz.xenondevs.invui.Click;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.AbstractPagedGuiBoundItem;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.item.ItemProvider;

/**
 * Previous/next page controls for an InvUI {@link PagedGui}. Each binds to its gui automatically and
 * only shows when there is a page to move to.
 */
@NullMarked final class PageButtons {

    private PageButtons() {
    }

    static final class Previous extends AbstractPagedGuiBoundItem {
        @Override
        public ItemProvider getItemProvider(final Player viewer) {
            final PagedGui<?> gui = getGui();
            if (gui.getPage() <= 0) {
                return ItemProvider.EMPTY;
            }
            return new ItemBuilder(Material.ARROW).setName(Messages.format("<yellow>Previous page"));
        }

        @Override
        public void handleClick(final ClickType clickType, final Player player, final Click click) {
            final PagedGui<?> gui = getGui();
            if (gui.getPage() > 0) {
                gui.setPage(gui.getPage() - 1);
            }
        }
    }

    static final class Next extends AbstractPagedGuiBoundItem {
        @Override
        public ItemProvider getItemProvider(final Player viewer) {
            final PagedGui<?> gui = getGui();
            if (gui.getPage() + 1 >= gui.getPageCount()) {
                return ItemProvider.EMPTY;
            }
            return new ItemBuilder(Material.ARROW).setName(Messages.format("<yellow>Next page"));
        }

        @Override
        public void handleClick(final ClickType clickType, final Player player, final Click click) {
            final PagedGui<?> gui = getGui();
            if (gui.getPage() + 1 < gui.getPageCount()) {
                gui.setPage(gui.getPage() + 1);
            }
        }
    }
}
