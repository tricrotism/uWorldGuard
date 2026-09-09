package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.flags.*;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import java.util.function.Predicate;

/**
 * InvUI editor for a region's flags. Opens on a category landing page so the full flag set is never
 * shown at once: pick a category to edit just its flags, "Active" to edit only the flags currently
 * set, or "Search" to find a flag by name across every category. Within a list, state and boolean
 * flags cycle on left-click; typed flags prompt for a chat value; right-click clears any flag. Region
 * writes go through the thread-safe {@code setFlag}, and each item refreshes itself via {@code
 * notifyWindows}.
 */
@NullMarked
public final class FlagMenu {

    private final RegionManager manager;
    private final ProtectedRegion region;
    private final ChatInputService chatInput;

    public FlagMenu(
        final RegionManager manager, final ProtectedRegion region, final ChatInputService chatInput
    ) {
        this.manager = manager;
        this.region = region;
        this.chatInput = chatInput;
    }

    public void open(final Player player) {
        openLanding(player);
    }

    /**
     * Whether the region this menu edits has left the manager since it was opened. Writes to a
     * removed region land on an object nothing saves or consults, so the operator sees the flag take
     * and it does nothing; a chat prompt makes that window as long as they take to type.
     */
    private boolean gone(final Player player) {
        if (manager.getRegion(region.getId()) == region) {
            return false;
        }
        player.sendMessage(Messages.format("<red>That region no longer exists."));
        return true;
    }

    private void openLanding(final Player player) {
        final PagedGui<Item> gui = PagedGui.itemsBuilder()
            .setStructure(
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "A . R . . . . . C")
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('A', activeButton())
            .addIngredient('R', searchButton())
            .addIngredient('C', MenuItems.close())
            .setContent(categoryButtons())
            .build();
        window(player, Messages.format("<dark_gray>Flags: <aqua><id>",
            Placeholder.unparsed("id", region.getId())), gui);
    }

    private List<Item> categoryButtons() {
        final FlagCategory[] categories = FlagCategory.values();
        final CategoryCards cards = cards();
        final List<Item> items = new ArrayList<>(categories.length);
        for (final FlagCategory category : categories) {
            final int index = category.ordinal();
            items.add(Item.builder()
                .setItemProvider(new ItemBuilder(iconFor(category))
                    .setName(cards.names()[index])
                    .addLoreLines(
                        cards.counts()[index],
                        Messages.format("<!i><dark_gray>Click to view")))
                .addClickHandler((item, click) -> openList(click.player(),
                    Messages.format("<dark_gray><name>",
                        Placeholder.unparsed("name", category.getDisplayName())),
                    flag -> flag.getCategory() == category))
                .build());
        }
        return items;
    }

    /**
     * The landing page's per-category name and flag count, both fixed for the life of the server:
     * flags only register during enable. Rendered on demand, the tiles cost a walk of every
     * registered flag per tile plus sixteen uncached MiniMessage parses — on every open and every
     * press of Back, for an answer that cannot change.
     */
    private record CategoryCards(Component[] names, Component[] counts) {}

    private static volatile @Nullable CategoryCards cards;

    /**
     * Built on first use rather than in a static initialiser, which would run before the integrations
     * have registered their flags. Two menus opening at once simply build identical tables.
     */
    private static CategoryCards cards() {
        CategoryCards cached = cards;
        if (cached != null) {
            return cached;
        }
        final FlagCategory[] categories = FlagCategory.values();
        final int[] tally = new int[categories.length];
        for (final Flag<?> flag : Flags.all()) {
            tally[flag.getCategory().ordinal()]++;
        }
        final Component[] names = new Component[categories.length];
        final Component[] counts = new Component[categories.length];
        for (final FlagCategory category : categories) {
            final int index = category.ordinal();
            names[index] = Messages.format("<!i><yellow><name>",
                Placeholder.unparsed("name", category.getDisplayName()));
            counts[index] = Messages.format("<!i><gray><white><count></white> flags",
                Placeholder.unparsed("count", Integer.toString(tally[index])));
        }
        cached = new CategoryCards(names, counts);
        cards = cached;
        return cached;
    }

    private Item activeButton() {
        return Item.builder()
            .setItemProvider(_ -> new ItemBuilder(Material.NETHER_STAR)
                .setName(Messages.format("<!i><yellow>Active flags"))
                .addLoreLines(
                    Messages.format("<!i><gray><white><count></white> set on this region",
                        Placeholder.unparsed("count", Integer.toString(region.getFlags().size()))),
                    Messages.format("<!i><dark_gray>Click to view only the flags you've set")))
            .addClickHandler((item, click) -> openList(click.player(),
                Messages.format("<dark_gray>Active flags"),
                flag -> region.getFlags().get(flag) != null))
            .build();
    }

    private Item searchButton() {
        return Item.builder()
            .setItemProvider(_ -> new ItemBuilder(Material.OAK_SIGN)
                .setName(Messages.format("<!i><yellow>Search"))
                .addLoreLines(
                    Messages.format("<!i><gray>Find a flag by name across all categories"),
                    Messages.format("<!i><dark_gray>Click, then type a query")))
            .addClickHandler((_, click) -> promptSearch(click.player()))
            .build();
    }

    private void promptSearch(final Player player) {
        player.closeInventory();
        player.sendMessage(Messages.format("<gray>Type a search query in chat, or <red>cancel</red>."));
        chatInput.await(player.getUniqueId(), raw -> {
            final String query = raw.trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                openLanding(player);
                return;
            }
            openList(player, Messages.format("<dark_gray>Search: <aqua><query>",
                Placeholder.unparsed("query", query)), flag -> flag.getName().contains(query));
        });
    }

    private void openList(final Player player, final Component title, final Predicate<Flag<?>> filter) {
        final PagedGui<Item> gui = PagedGui.itemsBuilder()
            .setStructure(
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "x x x x x x x x x",
                "< B . . C . . . >")
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('<', new PageButtons.Previous())
            .addIngredient('>', new PageButtons.Next())
            .addIngredient('B', backButton())
            .addIngredient('C', MenuItems.close())
            .setContent(flagItems(filter))
            .build();
        window(player, title, gui);
    }

    private Item backButton() {
        return Item.builder()
            .setItemProvider(new ItemBuilder(Material.OAK_DOOR).setName(Messages.format("<!i><yellow>Back")))
            .addClickHandler((_, click) -> openLanding(click.player()))
            .build();
    }

    /**
     * The name and the "Accepts:" hint depend only on the flag, so they are parsed once here rather
     * than inside the provider, which re-runs every time the item repaints itself after a click.
     */
    private List<Item> flagItems(final Predicate<Flag<?>> filter) {
        final List<Item> items = new ArrayList<>();
        for (final Flag<?> flag : Flags.all()) {
            if (!filter.test(flag)) {
                continue;
            }
            final Component name = Messages.format("<!i><yellow><flag>",
                Placeholder.unparsed("flag", flag.getName()));
            final Component accepts = Messages.format("<!i><gray>Accepts: <white><hint>",
                Placeholder.unparsed("hint", typeHint(flag)));
            items.add(Item.builder()
                .setItemProvider(_ -> provider(flag, name, accepts))
                .addClickHandler((item, click) -> onClick(flag, click.player(), click.clickType(), item))
                .build());
        }
        return items;
    }

    private void window(final Player player, final Component title, final PagedGui<Item> gui) {
        Window.builder()
            .setViewer(player)
            .setTitle(title)
            .setUpperGui(gui)
            .build()
            .open();
    }

    private ItemProvider provider(final Flag<?> flag, final Component name, final Component accepts) {
        final Object value = region.getFlags().get(flag);
        final boolean toggle = flag instanceof StateFlag || flag instanceof BooleanFlag;
        final RegionGroup group = region.getFlagGroup(flag);
        return new ItemBuilder(materialFor(value))
            .setName(name)
            .addLoreLines(
                valueLine(value),
                group == RegionGroup.ALL
                    ? accepts
                    : Messages.format("<!i><gray>Applies to: <gold><group>",
                    Placeholder.unparsed("group", group.serialized())),
                Component.empty(),
                Messages.format(toggle
                    ? "<!i><dark_gray>Left-click <gray>cycle allow / deny / unset"
                    : "<!i><dark_gray>Left-click <gray>type a new value in chat"),
                Messages.format("<!i><dark_gray>Right-click <gray>clear back to default"));
    }

    /**
     * The current value, coloured by meaning so a wall of flags reads at a glance: green permits,
     * red denies, grey is untouched and follows the server default.
     *
     * <p>The colour is the template's; the value itself is a placeholder. String-typed flags hold
     * whatever an operator typed into chat — greeting text, deny messages — and concatenating that
     * into the template would parse it as markup, which for a malformed tag throws while the item is
     * being built.
     */
    private static Component valueLine(final @Nullable Object value) {
        if (value == null) {
            return Messages.format("<!i><gray>Now: <dark_gray><i>not set (uses default)</i>");
        }
        final String colour = value == State.ALLOW || Boolean.TRUE.equals(value) ? "<green>"
            : value == State.DENY || Boolean.FALSE.equals(value) ? "<red>" : "<white>";
        return Messages.format("<!i><gray>Now: " + colour + "<value>",
            Placeholder.unparsed("value", String.valueOf(value)));
    }

    private static String typeHint(final Flag<?> flag) {
        final String declared = flag.getValueHint();
        if (declared != null) {
            return declared;
        }
        if (flag instanceof StateFlag) {
            return "allow / deny";
        }
        if (flag instanceof BooleanFlag) {
            return "true / false";
        }
        if (flag instanceof IntegerFlag || flag instanceof DoubleFlag) {
            return "a number";
        }
        if (flag instanceof MaterialSetFlag) {
            return "item list, e.g. DIAMOND_SWORD, BOW";
        }
        if (flag instanceof PotionEffectSetFlag) {
            return "effects, e.g. SPEED:1, JUMP";
        }
        if (flag instanceof EntityTypeSetFlag) {
            return "entity list, e.g. CREEPER, minecraft:zombie";
        }
        if (flag instanceof StringSetFlag) {
            return "commands, e.g. home, tp";
        }
        return "text";
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
        if (MenuItems.denied(player, MenuItems.FLAG) || gone(player)) {
            return;
        }
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

    /**
     * Read-then-write, so two operators cycling the same flag from their own menus at the same moment
     * can lose one click; the loser sees the result on their next repaint and clicks again. Left as
     * is: an atomic compute on the region would have to exist on the API for one GUI's benefit, and
     * the last write is a valid outcome of two simultaneous clicks either way.
     */
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
            if (gone(player)) {
                return;
            }
            if (applyValue(region, flag, value, player)) {
                manager.markDirty();
            } else {
                player.sendMessage(Messages.format("<red>Invalid value for <aqua>" + flag.getName() + "</aqua>."));
            }
            open(player);
        });
    }

    private static <T> boolean applyValue(
        final ProtectedRegion region, final Flag<T> flag, final String value, final Player setter
    ) {
        final T parsed = flag.parse(value, setter);
        if (parsed == null) {
            return false;
        }
        region.setFlag(flag, parsed);
        return true;
    }

    private static Material iconFor(final FlagCategory category) {
        return switch (category) {
            case PROTECTION -> Material.SHIELD;
            case ENVIRONMENT -> Material.OAK_SAPLING;
            case MOBS -> Material.ZOMBIE_HEAD;
            case MOVEMENT -> Material.LEATHER_BOOTS;
            case MESSAGES -> Material.WRITABLE_BOOK;
            case ITEMS -> Material.CHEST;
            case ENTRY -> Material.IRON_DOOR;
            case PLAYER -> Material.PLAYER_HEAD;
            case EXTENSION -> Material.COMMAND_BLOCK;
        };
    }
}
