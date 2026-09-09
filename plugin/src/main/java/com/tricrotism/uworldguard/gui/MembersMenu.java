package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
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

    private final Plugin plugin;
    private final RegionManager manager;
    private final ProtectedRegion region;
    private final String regionId;
    private final ChatInputService chatInput;
    private @Nullable PagedGui<Item> gui;

    public MembersMenu(final Plugin plugin, final RegionManager manager, final ProtectedRegion region,
                       final ChatInputService chatInput) {
        this.plugin = plugin;
        this.manager = manager;
        this.region = region;
        this.regionId = region.getId();
        this.chatInput = chatInput;
    }

    public void open(final Player player) {
        final PagedGui<Item> built = PagedGui.items()
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

        Window.single()
            .setViewer(player)
            .setTitle(MenuItems.wrap(Messages.format("<dark_gray>Members: <aqua><id>",
                Placeholder.unparsed("id", region.getId()))))
            .setGui(built)
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
        final ItemProvider provider = new ItemBuilder(Material.PLAYER_HEAD)
            .setDisplayName(MenuItems.wrap(Messages.format("<!i><yellow><name>",
                Placeholder.unparsed("name", nameOf(uuid)))))
            .addLoreLines(
                MenuItems.wrap(Messages.format(owner ? "<!i><gray>Owner" : "<!i><gray>Member")),
                MenuItems.wrap(Messages.format("<!i><dark_gray>Click to remove")));
        return MenuItems.clickable(() -> provider, (item, click) -> {
            final Player clicker = click.getPlayer();
            if (MenuItems.denied(clicker, MenuItems.MEMBERS)) {
                return;
            }
            if (manager.getRegion(regionId) != region) {
                clicker.sendMessage(Messages.format("<red>Region <aqua><id></aqua> no longer exists.",
                    Placeholder.unparsed("id", regionId)));
                return;
            }
            (owner ? region.getOwners() : region.getMembers()).removePlayer(uuid);
            manager.markDirty();
            if (gui != null) {
                gui.setContent(entries());
            }
        });
    }

    private Item addItem(final boolean owner) {
        final ItemProvider provider = new ItemBuilder(owner ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET)
            .setDisplayName(MenuItems.wrap(Messages.format(owner ? "<!i><green>Add owner" : "<!i><green>Add member")))
            .addLoreLines(MenuItems.wrap(Messages.format("<!i><dark_gray>Click, then type a player name")));
        return MenuItems.clickable(() -> provider, (item, click) -> promptAdd(click.getPlayer(), owner));
    }

    private void promptAdd(final Player player, final boolean owner) {
        if (MenuItems.denied(player, MenuItems.MEMBERS)) {
            return;
        }
        player.closeInventory();
        player.sendMessage(Messages.format("<gray>Type the player name to add, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), name ->
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                final OfflinePlayer target = Bukkit.getOfflinePlayer(name);
                if (!target.isOnline() && !target.hasPlayedBefore()) {
                    player.sendMessage(Messages.format("<red>No player named <aqua><player></aqua> "
                        + "has played here.", Placeholder.unparsed("player", name)));
                    player.getScheduler().run(plugin, t -> open(player), null);
                    return;
                }
                if (manager.getRegion(regionId) != region) {
                    player.sendMessage(Messages.format("<red>Region <aqua><id></aqua> no longer exists.",
                        Placeholder.unparsed("id", regionId)));
                    return;
                }
                final DefaultDomain domain = owner ? region.getOwners() : region.getMembers();
                domain.addPlayer(target.getUniqueId());
                manager.markDirty();
                player.sendMessage(Messages.format("<green>Added <aqua><player></aqua> as <role>.",
                    Placeholder.unparsed("player", name),
                    Placeholder.unparsed("role", owner ? "owner" : "member")));
                player.getScheduler().run(plugin, t -> open(player), null);
            }));
    }

    /**
     * A display name for a trusted player. Online is a field read; offline is not resolved.
     *
     * <p>{@code OfflinePlayer.getName()} reads and decompresses that player's {@code .dat} off disk
     * despite looking like a getter, and this runs once per entry every time the menu is built —
     * including the rebuild after each removal click, on the thread the click arrived on. A short
     * uuid is a worse label than a name, but it is not worth blocking a tick per member to avoid.
     */
    private static String nameOf(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        return online != null ? online.getName() : uuid.toString().substring(0, 8);
    }
}
