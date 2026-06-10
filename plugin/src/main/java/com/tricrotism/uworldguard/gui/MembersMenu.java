package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.gui.Markers;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * InvUI owner/member editor for a region. Owners and members are listed as heads; clicking one
 * removes it. The two add buttons prompt for a name in chat. Domain edits are thread-safe.
 */
@NullMarked
public final class MembersMenu {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final RegionManager manager;
    private final ProtectedRegion region;
    private final ChatInputService chatInput;
    private @Nullable PagedGui<Item> gui;

    public MembersMenu(final Plugin plugin, final RegionManager manager, final ProtectedRegion region,
                       final ChatInputService chatInput) {
        this.plugin = plugin;
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
                "< O M C . . . . >")
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('<', new PageButtons.Previous())
            .addIngredient('>', new PageButtons.Next())
            .addIngredient('O', addItem(true))
            .addIngredient('M', addItem(false))
            .addIngredient('C', MenuItems.close())
            .setContent(entries())
            .build();
        this.gui = built;

        Window.builder()
            .setViewer(player)
            .setTitle(MM.deserialize("<dark_gray>Members: <aqua>" + region.getId()))
            .setUpperGui(built)
            .build()
            .open();
    }

    private List<Item> entries() {
        final List<Item> items = new ArrayList<>();
        for (final UUID uuid : region.getOwners().getPlayers()) {
            items.add(entry(uuid, true));
        }
        for (final UUID uuid : region.getMembers().getPlayers()) {
            items.add(entry(uuid, false));
        }
        return items;
    }

    private Item entry(final UUID uuid, final boolean owner) {
        return Item.builder()
            .setItemProvider(new ItemBuilder(Material.PLAYER_HEAD)
                .setName(MM.deserialize("<!i><yellow>" + nameOf(uuid)))
                .addLoreLines(
                    MM.deserialize("<!i><gray>" + (owner ? "Owner" : "Member")),
                    Messages.format("<!i><dark_gray>Click to remove")))
            .addClickHandler((item, click) -> {
                (owner ? region.getOwners() : region.getMembers()).removePlayer(uuid);
                manager.markDirty();
                if (gui != null) {
                    gui.setContent(entries());
                }
            })
            .build();
    }

    private Item addItem(final boolean owner) {
        return Item.builder()
            .setItemProvider(new ItemBuilder(owner ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET)
                .setName(MM.deserialize("<!i><green>Add " + (owner ? "owner" : "member")))
                .addLoreLines(Messages.format("<!i><dark_gray>Click, then type a player name")))
            .addClickHandler((item, click) -> promptAdd(click.player(), owner))
            .build();
    }

    private void promptAdd(final Player player, final boolean owner) {
        player.closeInventory();
        player.sendMessage(Messages.format("<gray>Type the player name to add, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), name ->
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                final OfflinePlayer target = Bukkit.getOfflinePlayer(name);
                final DefaultDomain domain = owner ? region.getOwners() : region.getMembers();
                domain.addPlayer(target.getUniqueId());
                manager.markDirty();
                player.sendMessage(Messages.format("<green>Added <aqua>" + name + "</aqua> as "
                    + (owner ? "owner" : "member") + "."));
                player.getScheduler().run(plugin, t -> open(player), null);
            }));
    }

    private static String nameOf(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        final String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
