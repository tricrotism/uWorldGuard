package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.flags.*;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.gui.Markers;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * InvUI paged editor for a region's flags. State and boolean flags cycle on left-click; typed flags
 * (string, number, item-set) prompt for a chat value; right-click clears any flag. Region writes go
 * through the thread-safe {@code setFlag}, and each item refreshes itself via {@code notifyWindows}.
 */
@NullMarked
public final class FlagMenu {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RegionManager manager;
    private final ProtectedRegion region;
    private final ChatInputService chatInput;
    private @Nullable PagedGui<Item> gui;
    private @Nullable String search;
    private @Nullable FlagCategory categoryFilter;

    public FlagMenu(
        final RegionManager manager, final ProtectedRegion region, final ChatInputService chatInput
    ) {
        this.manager = manager;
        this.region = region;
        this.chatInput = chatInput;
    }

    public void open(final Player player) {
        final PagedGui<Item> built = PagedGui.itemsBuilder()
            .setStructure(
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "< . F . C . S . >")
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('<', new PageButtons.Previous())
            .addIngredient('>', new PageButtons.Next())
            .addIngredient('C', MenuItems.close())
            .addIngredient('F', categoryItem())
            .addIngredient('S', searchItem())
            .setContent(buildItems())
            .build();
        this.gui = built;

        Window.builder()
            .setViewer(player)
            .setTitle(MM.deserialize("<dark_gray>Flags: <aqua>" + region.getId()))
            .setUpperGui(built)
            .build()
            .open();
    }

    private List<Item> buildItems() {
        final String query = search;
        final FlagCategory category = categoryFilter;
        final List<Item> items = new ArrayList<>(Flags.all().size());
        for (final Flag<?> flag : Flags.all()) {
            if (category != null && flag.getCategory() != category) {
                continue;
            }
            if (query != null && !flag.getName().contains(query)) {
                continue;
            }
            items.add(Item.builder()
                .setItemProvider(_ -> provider(flag))
                .addClickHandler((item, click) -> onClick(flag, click.player(), click.clickType(), item))
                .build());
        }
        return items;
    }

    private Item categoryItem() {
        return Item.builder()
            .setItemProvider(_ -> new ItemBuilder(Material.BOOKSHELF)
                .setName(Messages.format("<!i><yellow>Category"))
                .addLoreLines(
                    MM.deserialize("<!i><gray>Showing: <white>"
                        + (categoryFilter == null ? "All" : categoryFilter.getDisplayName())),
                    Messages.format("<!i><dark_gray>Left-click: next"),
                    Messages.format("<!i><dark_gray>Right-click: all")))
            .addClickHandler((item, click) -> {
                categoryFilter = click.clickType().isRightClick() ? null : nextCategory(categoryFilter);
                refresh();
                item.notifyWindows();
            })
            .build();
    }

    private Item searchItem() {
        return Item.builder()
            .setItemProvider(_ -> new ItemBuilder(Material.OAK_SIGN)
                .setName(Messages.format("<!i><yellow>Search"))
                .addLoreLines(
                    MM.deserialize("<!i><gray>Query: <white>" + (search == null ? "none" : search)),
                    Messages.format("<!i><dark_gray>Left-click: set query (chat)"),
                    Messages.format("<!i><dark_gray>Right-click: clear")))
            .addClickHandler((item, click) -> {
                if (click.clickType().isRightClick()) {
                    search = null;
                    refresh();
                    item.notifyWindows();
                } else {
                    promptSearch(click.player());
                }
            })
            .build();
    }

    private void promptSearch(final Player player) {
        player.closeInventory();
        player.sendMessage(Messages.format("<gray>Type a search query in chat, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), query -> {
            final String trimmed = query.trim().toLowerCase(Locale.ROOT);
            search = trimmed.isEmpty() ? null : trimmed;
            open(player);
        });
    }

    private void refresh() {
        if (gui != null) {
            gui.setContent(buildItems());
        }
    }

    private static @Nullable FlagCategory nextCategory(final @Nullable FlagCategory current) {
        final FlagCategory[] values = FlagCategory.values();
        if (current == null) {
            return values[0];
        }
        final int next = current.ordinal() + 1;
        return next < values.length ? values[next] : null;
    }

    private ItemProvider provider(final Flag<?> flag) {
        final Object value = region.getFlags().get(flag);
        final boolean toggle = flag instanceof StateFlag || flag instanceof BooleanFlag;
        return new ItemBuilder(materialFor(value))
            .setName(MM.deserialize("<!i><yellow>" + flag.getName()))
            .addLoreLines(
                MM.deserialize("<!i><gray>Value: <white>" + (value == null ? "unset" : String.valueOf(value))),
                Messages.format(toggle ? "<!i><dark_gray>Left-click: cycle" : "<!i><dark_gray>Left-click: set value (chat)"),
                Messages.format("<!i><dark_gray>Right-click: clear"));
    }

    private static Material materialFor(final @Nullable Object value) {
        if (value == State.ALLOW || Boolean.TRUE.equals(value)) {
            return Material.LIME_DYE;
        }
        if (value == State.DENY || Boolean.FALSE.equals(value)) {
            return Material.RED_DYE;
        }
        return value == null ? Material.LIGHT_GRAY_DYE : Material.WRITABLE_BOOK;
    }

    private void onClick(final Flag<?> flag, final Player player, final ClickType clickType, final Item item) {
        if (clickType.isRightClick()) {
            region.setFlag(flag, null);
            manager.markDirty();
            item.notifyWindows();
            return;
        }
        if (flag instanceof StateFlag stateFlag) {
            cycleState(stateFlag);
            manager.markDirty();
            item.notifyWindows();
        } else if (flag instanceof BooleanFlag booleanFlag) {
            cycleBoolean(booleanFlag);
            manager.markDirty();
            item.notifyWindows();
        } else {
            promptValue(player, flag);
        }
    }

    private void cycleState(final StateFlag flag) {
        final Object current = region.getFlags().get(flag);
        final State next = current == null ? State.ALLOW : current == State.ALLOW ? State.DENY : null;
        region.setFlag(flag, next);
    }

    private void cycleBoolean(final BooleanFlag flag) {
        final Object current = region.getFlags().get(flag);
        final Boolean next = current == null ? Boolean.TRUE : (Boolean) current ? Boolean.FALSE : null;
        region.setFlag(flag, next);
    }

    private void promptValue(final Player player, final Flag<?> flag) {
        player.closeInventory();
        player.sendMessage(Messages.format(
            "<gray>Type a new value for <aqua>" + flag.getName() + "</aqua> in chat, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), value -> {
            if (applyValue(region, flag, value)) {
                manager.markDirty();
            } else {
                player.sendMessage(Messages.format("<red>Invalid value for <aqua>" + flag.getName() + "</aqua>."));
            }
            open(player);
        });
    }

    private static <T> boolean applyValue(final ProtectedRegion region, final Flag<T> flag, final String value) {
        final T parsed = flag.parse(value);
        if (parsed == null) {
            return false;
        }
        region.setFlag(flag, parsed);
        return true;
    }
}
