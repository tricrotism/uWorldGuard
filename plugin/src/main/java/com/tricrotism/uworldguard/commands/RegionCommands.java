package com.tricrotism.uworldguard.commands;

import com.tricrotism.uworldguard.UWorldGuard;
import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.*;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.gui.ChatInputService;
import com.tricrotism.uworldguard.gui.FlagMenu;
import com.tricrotism.uworldguard.gui.RegionMenu;
import com.tricrotism.uworldguard.gui.SettingsMenu;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.selection.Selection;
import com.tricrotism.uworldguard.selection.SelectionService;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.util.BlockVector3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.paper.util.sender.PaperSimpleSenderMapper;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Region management commands, registered through Cloud's annotation parser.
 */
@NullMarked
public final class RegionCommands {

    private final UWorldGuard plugin;
    private final RegionContainerImpl container;
    private final SelectionService selection;
    private final ChatInputService chatInput;
    private final MessageService messages;
    private @Nullable PaperCommandManager<Source> manager;

    public RegionCommands(
        final UWorldGuard plugin, final RegionContainerImpl container, final SelectionService selection,
        final ChatInputService chatInput, final MessageService messages
    ) {
        this.plugin = plugin;
        this.container = container;
        this.selection = selection;
        this.chatInput = chatInput;
        this.messages = messages;
    }

    public void register(final Object... extraHandlers) {
        this.manager = PaperCommandManager
            .builder(PaperSimpleSenderMapper.simpleSenderMapper())
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin);
        final AnnotationParser<Source> parser = new AnnotationParser<>(manager, Source.class);
        parser.parse(this);
        for (final Object handler : extraHandlers) {
            parser.parse(handler);
        }
    }

    /**
     * One section of the help output: the word a player types after {@code /uwg help}, the heading it
     * prints under, and a line saying what the section is for.
     */
    private record Section(String key, String title, String summary) {}

    private static final List<Section> SECTIONS = List.of(
        new Section("regions", "Regions", "make, reshape and remove regions"),
        new Section("inspect", "Inspecting", "see what is here and what it does"),
        new Section("flags", "Flags", "set flags, priority and inheritance"),
        new Section("people", "Owners & members", "decide who may build"),
        new Section("menus", "Menus", "click through it instead of typing"),
        new Section("admin", "Administration", "reload, import, bypass, diagnostics"),
        new Section("other", "Other", "everything else"));

    /**
     * Which section a command belongs to, keyed by its first literal. Anything not listed falls into
     * "other", so a newly added command still shows up without touching this map.
     */
    private static final Map<String, String> HELP_SECTIONS = Map.ofEntries(
        Map.entry("define", "regions"),
        Map.entry("redefine", "regions"),
        Map.entry("remove", "regions"),
        Map.entry("list", "inspect"),
        Map.entry("info", "inspect"),
        Map.entry("here", "inspect"),
        Map.entry("flag", "flags"),
        Map.entry("priority", "flags"),
        Map.entry("parent", "flags"),
        Map.entry("owner", "people"),
        Map.entry("member", "people"),
        Map.entry("menu", "menus"),
        Map.entry("settings", "menus"),
        Map.entry("bypass", "admin"),
        Map.entry("reload", "admin"),
        Map.entry("migrate", "admin"),
        Map.entry("compat", "admin"));

    /**
     * Spellings kept working for anyone who already types them — WorldGuard's own names, and the ones
     * uWorldGuard shipped before the shapes moved behind {@code define}. They do the same thing as the
     * command that replaced them, and are left out of the help so the list stays the short one.
     */
    private static final Set<String> LEGACY_NAMES = Set.of(
        "define-cylinder", "define-sphere", "define-polygon",
        "redefine-cylinder", "redefine-sphere", "redefine-polygon",
        "addowner", "removeowner", "addmember", "removemember",
        "setparent", "removeparent");

    /**
     * The three commands worth knowing before any of the others.
     */
    private static final List<String> STARTERS = List.of("/uwg define <id>", "/uwg here", "/uwg menu");

    /**
     * The landing page: three commands to start with, then one clickable row per section. Printing
     * every command at once put two dozen lines of syntax on screen before a player knew which of
     * them they wanted, and the useful ones scrolled away with the rest.
     */
    @Command("uworldguard|uwg|worldguard|wg")
    @CommandDescription("Show what uWorldGuard can do")
    public void help(final Source sender) {
        final Map<String, List<Component>> sections = visibleCommands(sender);
        if (sections == null) return;

        Component message = Component.text("uWorldGuard", NamedTextColor.AQUA)
            .append(Component.text("  —  click a section to see its commands", CommandText.DESCRIPTION))
            .append(Component.newline())
            .append(CommandText.header("Start here"));
        for (final String starter : STARTERS) {
            message = message.append(Component.newline()).append(Component.text("  ")).append(
                CommandText.suggestable(CommandText.syntax(starter), starter + " ",
                    "Click to put this in your chat box"));
        }

        message = message.append(Component.newline()).append(CommandText.header("Sections"));
        for (final Section section : SECTIONS) {
            final List<Component> lines = sections.get(section.key());
            if (lines == null) continue;
            message = message.append(Component.newline()).append(CommandText.runnable(
                Component.text("  " + section.title(), CommandText.LITERAL)
                    .append(Component.text(" (" + lines.size() + ")", CommandText.PUNCTUATION))
                    .append(Component.text("  " + section.summary(), CommandText.DESCRIPTION)),
                "/uwg help " + section.key(),
                "/uwg help " + section.key()));
        }

        sender.source().sendMessage(message.append(Component.newline()).append(CommandText.runnable(
            Component.text("  everything at once", CommandText.DESCRIPTION),
            "/uwg help all", "/uwg help all")));
    }

    /**
     * One section's commands, or every one of them for {@code all}.
     */
    @Command("uworldguard|uwg|worldguard|wg help [section]")
    @CommandDescription("Show the commands in one section, or all of them")
    public void helpSection(
        final Source sender,
        @Argument(value = "section", suggestions = "help-sections") final @Nullable String name
    ) {
        if (name == null) {
            help(sender);
            return;
        }
        final Map<String, List<Component>> sections = visibleCommands(sender);
        if (sections == null) return;

        final boolean all = "all".equalsIgnoreCase(name);
        Component message = null;
        for (final Section section : SECTIONS) {
            if (!all && !section.key().equalsIgnoreCase(name)) continue;
            final List<Component> lines = sections.get(section.key());
            if (lines == null) continue;

            message = message == null ? CommandText.header(section.title())
                : message.append(Component.newline()).append(CommandText.header(section.title()));
            for (final Component line : lines) {
                message = message.append(Component.newline()).append(line);
            }
        }

        if (message == null) {
            error(sender, "No section named <aqua><name></aqua>. Run <aqua>/uwg</aqua> to see them.",
                Placeholder.unparsed("name", name));
            return;
        }
        sender.source().sendMessage(message.append(Component.newline()).append(
            Component.text("Colours: ", CommandText.DESCRIPTION)
                .append(Component.text("command", CommandText.LITERAL))
                .append(Component.text("  <", CommandText.PUNCTUATION))
                .append(Component.text("required", CommandText.REQUIRED))
                .append(Component.text(">  [", CommandText.PUNCTUATION))
                .append(Component.text("optional", CommandText.OPTIONAL))
                .append(Component.text("]", CommandText.PUNCTUATION))));
    }

    @Suggestions("help-sections")
    public List<String> suggestHelpSections(final CommandContext<Source> context, final String input) {
        final List<String> keys = new ArrayList<>(SECTIONS.size() + 1);
        for (final Section section : SECTIONS) {
            keys.add(section.key());
        }
        keys.add("all");
        return keys;
    }

    /**
     * Every command this sender may run, as rendered lines grouped by section key, with the legacy
     * spellings left out. {@code null} when the manager is not built yet.
     */
    private @Nullable Map<String, List<Component>> visibleCommands(final Source sender) {
        final PaperCommandManager<Source> mgr = this.manager;
        if (mgr == null) return null;

        final Map<String, List<Component>> sections = new HashMap<>();
        for (final org.incendo.cloud.Command<Source> command : mgr.commands()) {
            final List<CommandComponent<Source>> components = command.components();
            if (components.size() < 2) continue;
            if (!mgr.testPermission(sender, command.commandPermission()).allowed()) continue;

            final List<CommandComponent<Source>> arguments = components.subList(1, components.size());
            final String root = arguments.getFirst().name();
            if (LEGACY_NAMES.contains(root) || "help".equals(root)) continue;

            final String syntax = "/uwg " + mgr.commandSyntaxFormatter().apply(sender, arguments, null);
            Component line = CommandText.suggestable(
                CommandText.syntax(syntax), syntax + " ", "Click to put this in your chat box");
            final org.incendo.cloud.description.CommandDescription description = command.commandDescription();
            if (!description.isEmpty()) {
                line = line
                    .append(Component.text("  —  ", CommandText.PUNCTUATION))
                    .append(Component.text(
                        description.description().textDescription(), CommandText.DESCRIPTION));
            }
            sections.computeIfAbsent(HELP_SECTIONS.getOrDefault(root, "other"), _ -> new ArrayList<>())
                .add(line);
        }
        for (final List<Component> lines : sections.values()) {
            lines.sort(Comparator.comparing(line -> PlainTextComponentSerializer.plainText().serialize(line)));
        }
        return sections;
    }

    @Command("uworldguard|uwg|worldguard|wg define <id>")
    @CommandDescription("Define a region from your selection, cuboid or polygon")
    @Permission("uworldguard.region.define")
    public void define(final Source sender, @Argument("id") final String id) {
        fromSelection(sender, id, false);
    }

    @Command("uworldguard|uwg|worldguard|wg redefine <id>")
    @CommandDescription("Reshape a region to your selection, keeping its flags, members and priority")
    @Permission("uworldguard.region.redefine")
    public void redefine(
        final Source sender, @Argument(value = "id", suggestions = "region-ids") final String id
    ) {
        fromSelection(sender, id, true);
    }

    /**
     * Takes the shape from the selection itself, as WorldGuard does: a {@code //sel poly} selection
     * defines a polygon spanning the selection's own Y range, anything else the bounding cuboid.
     * {@code define-polygon} remains the way to give a polygon a Y range of your own.
     */
    private void fromSelection(final Source sender, final String id, final boolean replace) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final Selection sel = selection.getSelection(player);
        if (sel == null) {
            error(sender, "Make a selection first.");
            return;
        }

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        final List<BlockVector3> points = selection.getPolygon(player);
        if (points == null || points.size() < 3) {
            apply(sender, regionManager, new ProtectedCuboidRegion(id, sel.min(), sel.max()), replace);
            return;
        }

        try {
            apply(sender, regionManager,
                new ProtectedPolygonRegion(id, points, sel.min().y(), sel.max().y()), replace);
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    @Command("uworldguard|uwg|worldguard|wg define <id> cylinder <radiusX> <radiusZ> <minY> <maxY>")
    @Command("uworldguard|uwg|worldguard|wg define-cylinder <id> <radiusX> <radiusZ> <minY> <maxY>")
    @CommandDescription("Define a cylinder region at your location")
    @Permission("uworldguard.region.define")
    public void defineCylinder(
        final Source sender,
        @Argument("id") final String id,
        @Argument("radiusX") final int radiusX,
        @Argument("radiusZ") final int radiusZ,
        @Argument("minY") final int minY,
        @Argument("maxY") final int maxY
    ) {
        cylinder(sender, id, radiusX, radiusZ, minY, maxY, false);
    }

    @Command("uworldguard|uwg|worldguard|wg redefine <id> cylinder <radiusX> <radiusZ> <minY> <maxY>")
    @Command("uworldguard|uwg|worldguard|wg redefine-cylinder <id> <radiusX> <radiusZ> <minY> <maxY>")
    @CommandDescription("Reshape a region into a cylinder at your location, keeping its settings")
    @Permission("uworldguard.region.redefine")
    public void redefineCylinder(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument("radiusX") final int radiusX,
        @Argument("radiusZ") final int radiusZ,
        @Argument("minY") final int minY,
        @Argument("maxY") final int maxY
    ) {
        cylinder(sender, id, radiusX, radiusZ, minY, maxY, true);
    }

    private void cylinder(
        final Source sender, final String id, final int radiusX, final int radiusZ,
        final int minY, final int maxY, final boolean replace
    ) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        @NotNull final Location loc = player.getLocation();
        try {
            apply(sender, regionManager, new ProtectedCylinderRegion(
                id, loc.getBlockX(), loc.getBlockZ(), radiusX, radiusZ, minY, maxY), replace);
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    @Command("uworldguard|uwg|worldguard|wg define <id> sphere <radiusX> <radiusY> <radiusZ>")
    @Command("uworldguard|uwg|worldguard|wg define-sphere <id> <radiusX> <radiusY> <radiusZ>")
    @CommandDescription("Define a sphere region at your location")
    @Permission("uworldguard.region.define")
    public void defineSphere(
        final Source sender,
        @Argument("id") final String id,
        @Argument("radiusX") final int radiusX,
        @Argument("radiusY") final int radiusY,
        @Argument("radiusZ") final int radiusZ
    ) {
        sphere(sender, id, radiusX, radiusY, radiusZ, false);
    }

    @Command("uworldguard|uwg|worldguard|wg redefine <id> sphere <radiusX> <radiusY> <radiusZ>")
    @Command("uworldguard|uwg|worldguard|wg redefine-sphere <id> <radiusX> <radiusY> <radiusZ>")
    @CommandDescription("Reshape a region into a sphere at your location, keeping its settings")
    @Permission("uworldguard.region.redefine")
    public void redefineSphere(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument("radiusX") final int radiusX,
        @Argument("radiusY") final int radiusY,
        @Argument("radiusZ") final int radiusZ
    ) {
        sphere(sender, id, radiusX, radiusY, radiusZ, true);
    }

    private void sphere(
        final Source sender, final String id, final int radiusX, final int radiusY,
        final int radiusZ, final boolean replace
    ) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        final Location loc = player.getLocation();
        try {
            apply(sender, regionManager, new ProtectedSphereRegion(
                id, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), radiusX, radiusY, radiusZ), replace);
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    @Command("uworldguard|uwg|worldguard|wg define <id> polygon <minY> <maxY>")
    @Command("uworldguard|uwg|worldguard|wg define-polygon <id> <minY> <maxY>")
    @CommandDescription("Define a polygon region from your WorldEdit selection")
    @Permission("uworldguard.region.define")
    public void definePolygon(
        final Source sender,
        @Argument("id") final String id,
        @Argument("minY") final int minY,
        @Argument("maxY") final int maxY
    ) {
        polygon(sender, id, minY, maxY, false);
    }

    @Command("uworldguard|uwg|worldguard|wg redefine <id> polygon <minY> <maxY>")
    @Command("uworldguard|uwg|worldguard|wg redefine-polygon <id> <minY> <maxY>")
    @CommandDescription("Reshape a region to your WorldEdit polygon, keeping its settings")
    @Permission("uworldguard.region.redefine")
    public void redefinePolygon(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument("minY") final int minY,
        @Argument("maxY") final int maxY
    ) {
        polygon(sender, id, minY, maxY, true);
    }

    private void polygon(
        final Source sender, final String id, final int minY, final int maxY, final boolean replace
    ) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        final List<BlockVector3> points = selection.getPolygon(player);
        if (points == null || points.size() < 3) {
            error(sender, "Select a polygon with WorldEdit first (//sel poly).");
            return;
        }

        try {
            apply(sender, regionManager, new ProtectedPolygonRegion(id, points, minY, maxY), replace);
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    /**
     * The single point every {@code define*} and {@code redefine*} command funnels through, so the id
     * is validated once however the region was shaped. With {@code replace} the new shape takes over
     * the existing region's flags, members, priority and children instead of claiming a free id.
     */
    private void apply(
        final Source sender, final RegionManager regionManager, final ProtectedRegion region,
        final boolean replace
    ) {
        if (!ProtectedRegion.isValidId(region.getId())) {
            error(sender, "Region names may only use letters, digits, <aqua>_</aqua> and "
                    + "<aqua>-</aqua>, up to <aqua><max></aqua> characters.",
                Placeholder.unparsed("max", Integer.toString(ProtectedRegion.MAX_ID_LENGTH)));
            return;
        }

        if (replace) {
            if (GlobalProtectedRegion.ID.equalsIgnoreCase(region.getId())) {
                error(sender, "The global region covers the whole world and has no shape to redefine.");
                return;
            }
            if (regionManager.redefineRegion(region) == null) {
                error(sender, "No region named <aqua><id></aqua>.",
                    Placeholder.unparsed("id", region.getId()));
                return;
            }

            success(sender, "Redefined region <aqua><id></aqua>.",
                Placeholder.unparsed("id", region.getId()));
            return;
        }

        if (regionManager.addRegionIfAbsent(region) != null) {
            error(sender, "A region named <aqua><id></aqua> already exists.",
                Placeholder.unparsed("id", region.getId()));
            return;
        }

        success(sender, "Created region <aqua><id></aqua>.",
            Placeholder.unparsed("id", region.getId()));
    }

    @Command("uworldguard|uwg|worldguard|wg remove <id>")
    @CommandDescription("Remove a region")
    @Permission("uworldguard.region.remove")
    public void remove(final Source sender, @Argument(value = "id", suggestions = "region-ids") final String id) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        if (GlobalProtectedRegion.ID.equalsIgnoreCase(id)) {
            error(sender, "The global region cannot be removed.");
            return;
        }

        if (regionManager.removeRegion(id) == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        success(sender, "Removed region <aqua><id></aqua>.", Placeholder.unparsed("id", id));
    }

    /**
     * Regions shown per page of {@code /uwg list}, sized to leave the chat history readable.
     */
    private static final int PAGE_SIZE = 8;

    @Command("uworldguard|uwg|worldguard|wg list [page]")
    @CommandDescription("List regions in this world, a page at a time")
    @Permission("uworldguard.region.list")
    public void list(final Source sender, @Argument("page") final @Nullable Integer pageArg) {
        final int page = pageArg == null ? 1 : pageArg;
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        if (regionManager.size() == 0) {
            note(sender, "No regions in this world yet. Make a selection, then run /uwg define <name>.");
            return;
        }

        final List<ProtectedRegion> regions = new ArrayList<>(regionManager.getRegions());
        regions.sort(Comparator.comparing(ProtectedRegion::getId, String.CASE_INSENSITIVE_ORDER));

        final int pages = (regions.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        final int index = Math.clamp(page, 1, pages);
        final int from = (index - 1) * PAGE_SIZE;
        final int to = Math.min(from + PAGE_SIZE, regions.size());

        Component message = CommandText.header(regions.size()
                + (regions.size() == 1 ? " region in " : " regions in ") + world(sender))
            .append(Component.text("   page " + index + "/" + pages, CommandText.PUNCTUATION));

        for (int i = from; i < to; i++) {
            final ProtectedRegion region = regions.get(i);
            message = message.append(Component.newline()).append(CommandText.runnable(
                Component.text()
                    .append(Component.text("  • ", CommandText.PUNCTUATION))
                    .append(Component.text(region.getId(), CommandText.REGION))
                    .append(Component.text("  " + region.getType().name().toLowerCase(Locale.ROOT)
                            + ", priority " + region.getPriority()
                            + ", " + (region.getOwners().size() + region.getMembers().size()) + " trusted",
                        CommandText.DESCRIPTION))
                    .build(),
                "/uwg info " + region.getId(),
                "Click for details about " + region.getId()));
        }

        if (pages > 1) {
            message = message.append(Component.newline()).append(pager(index, pages));
        }
        sender.source().sendMessage(message);
    }

    /**
     * Previous/next controls for a paged listing. Both stay in place when unavailable but render dim
     * and inert, so the row does not jump around as you page through it.
     */
    private static Component pager(final int page, final int pages) {
        final Component previous = page > 1
            ? CommandText.runnable(Component.text("‹ prev", CommandText.LITERAL),
            "/uwg list " + (page - 1), "Page " + (page - 1))
            : Component.text("‹ prev", CommandText.PUNCTUATION);
        final Component next = page < pages
            ? CommandText.runnable(Component.text("next ›", CommandText.LITERAL),
            "/uwg list " + (page + 1), "Page " + (page + 1))
            : Component.text("next ›", CommandText.PUNCTUATION);
        return Component.text()
            .append(Component.text("  ", CommandText.DESCRIPTION))
            .append(previous)
            .append(Component.text("   ·   ", CommandText.PUNCTUATION))
            .append(next)
            .build();
    }

    private static String world(final Source sender) {
        return sender.source() instanceof Player player ? player.getWorld().getName() : "this world";
    }

    @Command("uworldguard|uwg|worldguard|wg here")
    @CommandDescription("Show the regions you are standing in")
    @Permission("uworldguard.region.info")
    public void here(final Source sender) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final ApplicableRegionSet set = container.createQuery().getApplicableRegions(player);
        final List<ProtectedRegion> regions = set.getRegions();
        if (regions.isEmpty()) {
            note(sender, "You are not standing in any region.");
            return;
        }

        final UUID uuid = player.getUniqueId();
        Component message = CommandText.header(regions.size()
            + (regions.size() == 1 ? " region here" : " regions here, strongest first"));
        for (final ProtectedRegion region : regions) {
            final String standing = region.isOwner(uuid) ? "owner"
                : region.isMember(uuid) ? "member" : "visitor";
            message = message.append(Component.newline()).append(CommandText.runnable(
                Component.text()
                    .append(Component.text("  • ", CommandText.PUNCTUATION))
                    .append(Component.text(region.getId(), CommandText.REGION))
                    .append(Component.text("  priority " + region.getPriority() + ", you are ",
                        CommandText.DESCRIPTION))
                    .append(Component.text(standing, "visitor".equals(standing)
                        ? CommandText.PUNCTUATION : CommandText.REQUIRED))
                    .build(),
                "/uwg info " + region.getId(),
                "Click for details about " + region.getId()));
        }
        message = message.append(Component.newline()).append(Component.text(
                "  You may build here: ", CommandText.DESCRIPTION))
            .append(set.canBuild(uuid)
                ? Component.text("yes", NamedTextColor.GREEN)
                : Component.text("no", NamedTextColor.RED));
        sender.source().sendMessage(message);
    }

    @Command("uworldguard|uwg|worldguard|wg bypass")
    @CommandDescription("Toggle your own region bypass off or on")
    @Permission(Bypass.NODE)
    public void bypass(final Source sender) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        if (Bypass.toggle(player)) {
            success(sender, "Bypass <green>on</green>, region protections no longer apply to you. "
                + "It turns itself off when you log out.");
        } else {
            note(sender, "Bypass <red>off</red>, you are treated as an ordinary player. "
                + "Run <aqua>/uwg bypass</aqua> again to turn it back on.");
        }
    }

    @Command("uworldguard|uwg|worldguard|wg info <id>")
    @CommandDescription("Show details about a region")
    @Permission("uworldguard.region.info")
    public void info(final Source sender, @Argument(value = "id", suggestions = "region-ids") final String id) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        final ProtectedRegion parent = region.getParent();
        final int flagCount = region.getFlags().size();

        Component card = CommandText.header("Region " + region.getId())
            .append(Component.newline())
            .append(CommandText.field("Type", region.getType().name().toLowerCase(Locale.ROOT)))
            .append(Component.newline())
            .append(CommandText.field("Priority", String.valueOf(region.getPriority())))
            .append(Component.newline())
            .append(CommandText.field("Parent", parent == null
                ? Component.text("none", CommandText.PUNCTUATION)
                : CommandText.runnable(Component.text(parent.getId(), CommandText.REGION),
                "/uwg info " + parent.getId(), "Click for details about " + parent.getId())))
            .append(Component.newline())
            .append(CommandText.field("Owners", String.valueOf(region.getOwners().size())))
            .append(Component.newline())
            .append(CommandText.field("Members", String.valueOf(region.getMembers().size())));

        card = card.append(Component.newline()).append(CommandText.field("Flags",
            CommandText.runnable(
                Component.text(flagCount + (flagCount == 0 ? " set" : " set — click to edit"),
                    CommandText.VALUE),
                "/uwg menu " + region.getId(),
                "Open the flag menu for " + region.getId())));

        sender.source().sendMessage(card);
    }

    @Command("uworldguard|uwg|worldguard|wg flag <id> <flag> [value]")
    @CommandDescription("Set or clear a flag on a region (-g to limit who it applies to)")
    @Permission("uworldguard.region.flag")
    public void flag(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "flag", suggestions = "flags") final String flagName,
        @org.incendo.cloud.annotations.Flag(value = "group", aliases = "g",
            suggestions = "flag-groups") final @Nullable String groupName,
        @Argument(value = "value", suggestions = "flag-values") @Greedy final @Nullable String value
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        final Flag<?> flag = Flags.get(flagName);
        if (flag == null) {
            error(sender, "Unknown flag <aqua><flag></aqua>.", Placeholder.unparsed("flag", flagName));
            return;
        }

        RegionGroup group = null;
        if (groupName != null) {
            group = RegionGroup.parse(groupName);
            if (group == null) {
                error(sender, "Unknown group <aqua><group></aqua>. Use one of: "
                        + "all, members, owners, nonmembers, nonowners, none.",
                    Placeholder.unparsed("group", groupName));
                return;
            }
        }

        if (value == null) {
            if (group != null) {
                if (region.getFlags().get(flag) == null) {
                    error(sender, "Flag <aqua><flag></aqua> is not set on that region, so there is"
                        + " nothing to qualify.", Placeholder.unparsed("flag", flag.getName()));
                    return;
                }
                region.setFlagGroup(flag, group);
                regionManager.markDirty();
                success(sender, "Flag <aqua><flag></aqua> now applies to <aqua><group></aqua>.",
                    Placeholder.unparsed("flag", flag.getName()),
                    Placeholder.unparsed("group", group.serialized()));
                return;
            }
            region.setFlag(flag, null);
            region.setFlagGroup(flag, null);
            regionManager.markDirty();
            success(sender, "Cleared flag <aqua><flag></aqua>.", Placeholder.unparsed("flag", flag.getName()));
            return;
        }

        if (!applyFlag(region, flag, value, asPlayer(sender))) {
            error(sender, "Invalid value for flag <aqua><flag></aqua>.", Placeholder.unparsed("flag", flag.getName()));
            return;
        }
        if (groupName != null) {
            region.setFlagGroup(flag, group);
        }

        regionManager.markDirty();
        final boolean qualified = group != null && group != RegionGroup.ALL;
        success(sender, "Set flag <aqua><flag></aqua> to <aqua><value></aqua>"
                + (qualified ? " for <aqua><group></aqua>" : "") + ".",
            Placeholder.unparsed("flag", flag.getName()),
            Placeholder.unparsed("value", value),
            Placeholder.unparsed("group", qualified ? group.serialized() : ""));
    }

    @Suggestions("flag-groups")
    public List<String> suggestFlagGroups(final CommandContext<Source> ctx, final String input) {
        return List.of("all", "members", "owners", "nonmembers", "nonowners", "none");
    }

    @Command("uworldguard|uwg|worldguard|wg priority <id> <priority>")
    @CommandDescription("Set a region's priority")
    @Permission("uworldguard.region.priority")
    public void priority(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument("priority") final int priority
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        region.setPriority(priority);
        regionManager.markDirty();
        success(sender, "Set priority of <aqua><id></aqua> to <aqua><priority></aqua>.",
            Placeholder.unparsed("id", id), Placeholder.unparsed("priority", Integer.toString(priority)));
    }

    @Command("uworldguard|uwg|worldguard|wg parent <id> [parent]")
    @Command("uworldguard|uwg|worldguard|wg setparent <id> [parent]")
    @CommandDescription("Set a region's parent, or clear it by naming no parent")
    @Permission("uworldguard.region.setparent")
    public void setParent(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "parent", suggestions = "region-ids") final @Nullable String parentId
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        if (parentId == null) {
            region.setParent(null);
            regionManager.markDirty();
            success(sender, "Cleared the parent of <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        final ProtectedRegion parent = regionManager.getRegion(parentId);
        if (parent == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", parentId));
            return;
        }

        try {
            region.setParent(parent);
        } catch (final IllegalArgumentException _) {
            error(sender, "That would create a circular parent relationship.");
            return;
        }

        regionManager.markDirty();
        success(sender, "Set parent of <aqua><id></aqua> to <aqua><parent></aqua>.",
            Placeholder.unparsed("id", id), Placeholder.unparsed("parent", parentId));
    }

    @Command("uworldguard|uwg|worldguard|wg removeparent|unsetparent <id>")
    @CommandDescription("Remove a region's parent")
    @Permission("uworldguard.region.setparent")
    public void removeParent(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        if (region.getParent() == null) {
            error(sender, "Region <aqua><id></aqua> has no parent.", Placeholder.unparsed("id", id));
            return;
        }

        region.setParent(null);
        regionManager.markDirty();
        success(sender, "Cleared the parent of <aqua><id></aqua>.", Placeholder.unparsed("id", id));
    }

    @Command("uworldguard|uwg|worldguard|wg menu")
    @CommandDescription("Open the region menu")
    @Permission("uworldguard.menu")
    public void menu(final Source sender) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        new RegionMenu(plugin, player.getWorld(), regionManager, selection, chatInput).open(player);
    }

    @Command("uworldguard|uwg|worldguard|wg settings")
    @CommandDescription("Open the settings menu")
    @Permission("uworldguard.settings")
    public void settings(final Source sender) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        new SettingsMenu(plugin, messages, chatInput).open(player);
    }

    @Command("uworldguard|uwg|worldguard|wg reload")
    @CommandDescription("Reload messages and config")
    @Permission("uworldguard.reload")
    public void reload(final Source sender) {
        messages.reload();
        plugin.reloadSettings();
        success(sender, "Reloaded messages, config, movement mode, and autosave interval. "
            + "<gray>(Storage backend and wand item still need a restart.)");
    }

    @Command("uworldguard|uwg|worldguard|wg menu <id>")
    @CommandDescription("Open the flag menu for a region")
    @Permission("uworldguard.menu")
    public void menu(final Source sender, @Argument(value = "id", suggestions = "region-ids") final String id) {
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        new FlagMenu(regionManager, region, chatInput).open(player);
    }

    @Command("uworldguard|uwg|worldguard|wg owner add <id> <player>")
    @Command("uworldguard|uwg|worldguard|wg addowner <id> <player>")
    @CommandDescription("Add an owner to a region")
    @Permission("uworldguard.region.members")
    public void addOwner(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "player", suggestions = "players") final String playerName
    ) {
        member(sender, id, playerName, true, true);
    }

    @Command("uworldguard|uwg|worldguard|wg owner remove <id> <player>")
    @Command("uworldguard|uwg|worldguard|wg removeowner <id> <player>")
    @CommandDescription("Remove an owner from a region")
    @Permission("uworldguard.region.members")
    public void removeOwner(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "player", suggestions = "players") final String playerName
    ) {
        member(sender, id, playerName, true, false);
    }

    @Command("uworldguard|uwg|worldguard|wg member add <id> <player>")
    @Command("uworldguard|uwg|worldguard|wg addmember <id> <player>")
    @CommandDescription("Add a member to a region")
    @Permission("uworldguard.region.members")
    public void addMember(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "player", suggestions = "players") final String playerName
    ) {
        member(sender, id, playerName, false, true);
    }

    @Command("uworldguard|uwg|worldguard|wg member remove <id> <player>")
    @Command("uworldguard|uwg|worldguard|wg removemember <id> <player>")
    @CommandDescription("Remove a member from a region")
    @Permission("uworldguard.region.members")
    public void removeMember(
        final Source sender,
        @Argument(value = "id", suggestions = "region-ids") final String id,
        @Argument(value = "player", suggestions = "players") final String playerName
    ) {
        member(sender, id, playerName, false, false);
    }

    private void member(
        final Source sender, final String id, final String playerName, final boolean owner, final boolean add
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            final OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            if (add && !target.isOnline() && !target.hasPlayedBefore()) {
                error(sender, "No player named <aqua><player></aqua> has played here.",
                    Placeholder.unparsed("player", playerName));
                return;
            }
            if (regionManager.getRegion(id) != region) {
                error(sender, "No region named <aqua><id></aqua>.", Placeholder.unparsed("id", id));
                return;
            }
            final UUID uuid = target.getUniqueId();
            final DefaultDomain domain = owner ? region.getOwners() : region.getMembers();
            if (add) {
                domain.addPlayer(uuid);
            } else {
                domain.removePlayer(uuid);
            }

            regionManager.markDirty();
            success(sender, (add ? "Added " : "Removed ") + "<aqua><player></aqua> "
                    + (add ? "to" : "from") + " " + (owner ? "owners" : "members")
                    + " of <aqua><id></aqua>.",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("id", id));
        });
    }

    /**
     * Suggestions are filtered against what has been typed rather than returned whole. A completion
     * request arrives on every keystroke, so on a world with many thousands of regions the unfiltered
     * form allocated a string per region per key pressed; matching here makes the cost scale with the
     * answer instead of with the world.
     */
    @Suggestions("region-ids")
    public List<String> suggestRegionIds(final CommandContext<Source> ctx, final String input) {
        if (!(ctx.sender().source() instanceof Player player)) return List.of();

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) return List.of();

        final String prefix = input.toLowerCase(Locale.ROOT);
        final List<String> ids = new ArrayList<>();
        for (final ProtectedRegion region : regionManager.getRegions()) {
            final String id = region.getId();
            if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Suggestions("flags")
    public List<String> suggestFlags(final CommandContext<Source> ctx, final String input) {
        final String prefix = input.toLowerCase(Locale.ROOT);
        final List<String> names = new ArrayList<>();
        for (final Flag<?> flag : Flags.all()) {
            if (flag.getName().startsWith(prefix)) {
                names.add(flag.getName());
            }
        }
        return names;
    }

    @Suggestions("players")
    public List<String> suggestPlayers(final CommandContext<Source> ctx, final String input) {
        final String prefix = input.toLowerCase(Locale.ROOT);
        final List<String> names = new ArrayList<>();
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            final String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Value suggestions for {@code /uwg flag}. Where a flag has no closed set of values, the
     * suggestion is a shaped example rather than nothing, so the expected format is discoverable from
     * the command line instead of only from the menu's "Accepts:" line.
     */
    @Suggestions("flag-values")
    public List<String> suggestFlagValues(final CommandContext<Source> ctx, final String input) {
        final Flag<?> flag = Flags.get(ctx.getOrDefault("flag", ""));
        if (flag == null) return List.of();

        final List<String> declared = flag.getValueSuggestions();
        if (!declared.isEmpty()) return declared;

        if (flag instanceof StateFlag) return List.of("allow", "deny");
        if (flag instanceof BooleanFlag) return List.of("true", "false");
        if (flag == Flags.GAME_MODE) return List.of("survival", "creative", "adventure", "spectator");
        if (flag instanceof StringSetFlag) return List.of("home,tp,spawn");
        if (flag instanceof PotionEffectSetFlag) return List.of("SPEED:1,NIGHT_VISION");
        if (flag instanceof MaterialSetFlag) return List.of("DIAMOND_SWORD,BOW");
        if (flag instanceof IntegerFlag || flag instanceof DoubleFlag) return List.of("1");
        if (flag == Flags.TELEPORT_ON_ENTRY || flag == Flags.TELEPORT_ON_EXIT
            || flag == Flags.RESPAWN_LOCATION || flag == Flags.JOIN_LOCATION) {
            return List.of("world,0,64,0");
        }

        return List.of();
    }

    private static <T> boolean applyFlag(
        final ProtectedRegion region, final Flag<T> flag, final String value, final @Nullable Player setter
    ) {
        final T parsed = flag.parse(value, setter);
        if (parsed == null) return false;

        region.setFlag(flag, parsed);
        return true;
    }

    private @Nullable RegionManager managerFor(final Source sender) {
        final Player player = asPlayer(sender);
        if (player == null) return null;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
        }
        return regionManager;
    }

    private @Nullable Player asPlayer(final Source sender) {
        if (sender.source() instanceof Player player) return player;

        error(sender, "Only players can use this command.");
        return null;
    }

    /**
     * The three senders take resolvers rather than an interpolated string on purpose: ids, flag
     * names and flag values all arrive from the command line, and pasting them into a MiniMessage
     * template would let the sender's own input open tags in the reply. {@code <id>} and friends in
     * the templates below are placeholders filled by {@link Placeholder#unparsed}, never markup.
     */
    private static void error(final Source sender, final String message, final TagResolver... resolvers) {
        sender.source().sendMessage(Messages.format("<red>" + message, resolvers));
    }

    private static void success(final Source sender, final String message, final TagResolver... resolvers) {
        sender.source().sendMessage(Messages.format("<green>" + message, resolvers));
    }

    private static void note(final Source sender, final String message, final TagResolver... resolvers) {
        sender.source().sendMessage(Messages.format("<gray>" + message, resolvers));
    }
}
