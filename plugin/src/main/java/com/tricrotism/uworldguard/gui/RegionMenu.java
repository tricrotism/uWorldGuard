package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionType;
import com.tricrotism.uworldguard.selection.Selection;
import com.tricrotism.uworldguard.selection.SelectionService;
import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.util.BlockVector3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.gui.Markers;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * InvUI region browser for one world: left-click a region to edit its flags, right-click to teleport
 * to it, shift-right-click to delete; a create button makes a cuboid from the player's selection.
 */
@NullMarked
public final class RegionMenu {

    private final Plugin plugin;
    private final World world;
    private final RegionManager manager;
    private final SelectionService selection;
    private final ChatInputService chatInput;
    private @Nullable PagedGui<Item> gui;

    public RegionMenu(final Plugin plugin, final World world, final RegionManager manager,
                      final SelectionService selection, final ChatInputService chatInput) {
        this.plugin = plugin;
        this.world = world;
        this.manager = manager;
        this.selection = selection;
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
                "< . . C N . . . >")
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('<', new PageButtons.Previous())
            .addIngredient('>', new PageButtons.Next())
            .addIngredient('C', MenuItems.close())
            .addIngredient('N', createItem())
            .setContent(buildItems())
            .build();
        this.gui = built;

        Window.builder()
            .setViewer(player)
            .setTitle(Messages.format("<dark_gray>Regions: <aqua><world>",
                Placeholder.unparsed("world", world.getName())))
            .setUpperGui(built)
            .build()
            .open();
    }

    /**
     * A region's id and type cannot change, so those two lines are parsed once here rather than on
     * every repaint the item does; priority and the trusted counts are left in the provider because
     * they can be edited while the menu is open.
     */
    private List<Item> buildItems() {
        final Collection<ProtectedRegion> regions = manager.getRegions();
        final List<Item> items = new ArrayList<>(regions.size());
        for (final ProtectedRegion region : regions) {
            final Component name = Messages.format("<!i><yellow><id>",
                Placeholder.unparsed("id", region.getId()));
            final Component type = Messages.format("<!i><gray>Type: <white><type>",
                Placeholder.unparsed("type", region.getType().name().toLowerCase(Locale.ROOT)));
            items.add(Item.builder()
                .setItemProvider(viewer -> regionProvider(region, name, type))
                .addClickHandler((item, click) -> onClick(region, click.player(), click.clickType()))
                .build());
        }
        return items;
    }

    private ItemBuilder regionProvider(
        final ProtectedRegion region, final Component name, final Component type
    ) {
        return new ItemBuilder(materialFor(region.getType()))
            .setName(name)
            .addLoreLines(
                type,
                Messages.format("<!i><gray>Priority: <white><priority>",
                    Placeholder.unparsed("priority", Integer.toString(region.getPriority()))),
                Messages.format("<!i><gray>Owners: <white><owners> <gray>Members: <white><members>",
                    Placeholder.unparsed("owners", Integer.toString(region.getOwners().size())),
                    Placeholder.unparsed("members", Integer.toString(region.getMembers().size()))),
                Component.empty(),
                Messages.format("<!i><dark_gray>Left-click <gray>edit flags"),
                Messages.format("<!i><dark_gray>Right-click <gray>teleport here"),
                Messages.format("<!i><dark_gray>Shift + left <gray>owners & members"),
                Messages.format("<!i><dark_gray>Shift + right <red>delete this region"));
    }

    private static Material materialFor(final RegionType type) {
        return switch (type) {
            case CUBOID -> Material.GRASS_BLOCK;
            case POLYGON -> Material.MAP;
            case CYLINDER -> Material.CAULDRON;
            case SPHERE -> Material.SLIME_BALL;
            case GLOBAL -> Material.BEACON;
        };
    }

    private void onClick(final ProtectedRegion region, final Player player, final ClickType clickType) {
        if (clickType.isShiftClick() && clickType.isRightClick()) {
            if (region.getType() == RegionType.GLOBAL) {
                player.sendMessage(Messages.format("<red>The global region cannot be removed."));
                return;
            }
            if (MenuItems.denied(player, MenuItems.REMOVE)) {
                return;
            }
            manager.removeRegion(region.getId());
            if (gui != null) {
                gui.setContent(buildItems());
            }
            return;
        }
        if (clickType.isShiftClick() && clickType.isLeftClick()) {
            new MembersMenu(plugin, manager, region, chatInput).open(player);
            return;
        }
        if (clickType.isRightClick()) {
            teleport(player, region);
            return;
        }
        new FlagMenu(manager, region, chatInput).open(player);
    }

    private void teleport(final Player player, final ProtectedRegion region) {
        if (region.getType() == RegionType.GLOBAL) {
            player.sendMessage(Messages.format("<red>The global region has no location."));
            return;
        }
        final BlockVector3 min = region.getMinimumPoint();
        final BlockVector3 max = region.getMaximumPoint();
        final Location target = new Location(world,
            (min.x() + max.x()) / 2.0 + 0.5, max.y() + 1, (min.z() + max.z()) / 2.0 + 0.5);
        player.teleportAsync(target);
    }

    private Item createItem() {
        return Item.builder()
            .setItemProvider(new ItemBuilder(Material.EMERALD)
                .setName(Messages.format("<!i><green>Create region"))
                .addLoreLines(
                    Messages.format("<!i><gray>Cuboid from your current selection"),
                    Messages.format("<!i><dark_gray>Click, then type a name")))
            .addClickHandler((item, click) -> promptCreate(click.player()))
            .build();
    }

    private void promptCreate(final Player player) {
        if (MenuItems.denied(player, MenuItems.DEFINE)) {
            return;
        }
        final Selection sel = selection.getSelection(player);
        if (sel == null) {
            player.sendMessage(Messages.format("<red>Make a selection first."));
            return;
        }
        player.closeInventory();
        player.sendMessage(Messages.format("<gray>Type a name for the new region, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), name -> {
            if (!ProtectedRegion.isValidId(name)) {
                player.sendMessage(Messages.format("<red>Region names may only use letters, digits, "
                        + "<aqua>_</aqua> and <aqua>-</aqua>, up to <aqua><max></aqua> characters.",
                    Placeholder.unparsed("max", Integer.toString(ProtectedRegion.MAX_ID_LENGTH))));
            } else if (manager.addRegionIfAbsent(new ProtectedCuboidRegion(name, sel.min(), sel.max())) != null) {
                player.sendMessage(Messages.format("<red>A region named <aqua><id></aqua> already exists.",
                    Placeholder.unparsed("id", name)));
            } else {
                player.sendMessage(Messages.format("<green>Created region <aqua><id></aqua>.",
                    Placeholder.unparsed("id", name)));
            }
            open(player);
        });
    }
}
