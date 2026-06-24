package com.tricrotism.uworldguard.commands;

import com.tricrotism.uworldguard.UWorldGuard;
import com.tricrotism.uworldguard.domain.DefaultDomain;
import com.tricrotism.uworldguard.flags.BooleanFlag;
import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Command("uworldguard|uwg|worldguard|wg")
    public void help(final Source sender) {
        final PaperCommandManager<Source> mgr = this.manager;
        if (mgr == null) return;

        final List<String> lines = new ArrayList<>();
        for (final org.incendo.cloud.Command<Source> command : mgr.commands()) {
            final List<CommandComponent<Source>> components = command.components();
            if (components.size() < 2) continue;
            if (!mgr.testPermission(sender, command.commandPermission()).allowed()) continue;

            final String syntax = "/uwg " + mgr.commandSyntaxFormatter().apply(
                sender, components.subList(1, components.size()), null);
            final org.incendo.cloud.description.CommandDescription description = command.commandDescription();
            lines.add(description.isEmpty()
                ? syntax
                : syntax + "  -  " + description.description().textDescription());
        }
        lines.sort(null);

        Component message = Messages.format("<aqua>uWorldGuard</aqua> <gray>commands:");
        for (final String line : lines) {
            message = message.append(Component.newline())
                .append(Component.text(line, NamedTextColor.GRAY));
        }
        sender.source().sendMessage(message);
    }

    @Command("uworldguard|uwg|worldguard|wg define <id>")
    @CommandDescription("Define a cuboid region from your selection")
    @Permission("uworldguard.region.define")
    public void define(final Source sender, @Argument("id") final String id) {
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

        if (regionManager.hasRegion(id)) {
            error(sender, "A region named <aqua>" + id + "</aqua> already exists.");
            return;
        }

        create(sender, regionManager, new ProtectedCuboidRegion(id, sel.min(), sel.max()));
    }

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
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        @NotNull final Location loc = player.getLocation();
        try {
            create(sender, regionManager, new ProtectedCylinderRegion(
                id, loc.getBlockX(), loc.getBlockZ(), radiusX, radiusZ, minY, maxY));
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

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
        final Player player = asPlayer(sender);
        if (player == null) return;

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) {
            error(sender, "Regions are not loaded for this world.");
            return;
        }

        final Location loc = player.getLocation();
        try {
            create(sender, regionManager, new ProtectedSphereRegion(
                id, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), radiusX, radiusY, radiusZ));
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    @Command("uworldguard|uwg|worldguard|wg define-polygon <id> <minY> <maxY>")
    @CommandDescription("Define a polygon region from your WorldEdit selection")
    @Permission("uworldguard.region.define")
    public void definePolygon(
        final Source sender,
        @Argument("id") final String id,
        @Argument("minY") final int minY,
        @Argument("maxY") final int maxY
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
            create(sender, regionManager, new ProtectedPolygonRegion(id, points, minY, maxY));
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    private void create(
        final Source sender, final RegionManager regionManager, final ProtectedRegion region
    ) {
        if (regionManager.hasRegion(region.getId())) {
            error(sender, "A region named <aqua>" + region.getId() + "</aqua> already exists.");
            return;
        }

        regionManager.addRegion(region);
        success(sender, "Created region <aqua>" + region.getId() + "</aqua>.");
    }

    @Command("uworldguard|uwg|worldguard|wg remove <id>")
    @CommandDescription("Remove a region")
    @Permission("uworldguard.region.remove")
    public void remove(final Source sender, @Argument("id") final String id) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        if (GlobalProtectedRegion.ID.equalsIgnoreCase(id)) {
            error(sender, "The global region cannot be removed.");
            return;
        }

        if (regionManager.removeRegion(id) == null) {
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        success(sender, "Removed region <aqua>" + id + "</aqua>.");
    }

    @Command("uworldguard|uwg|worldguard|wg list")
    @CommandDescription("List all regions in this world")
    @Permission("uworldguard.region.list")
    public void list(final Source sender) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final StringBuilder sb = new StringBuilder();
        for (final ProtectedRegion region : regionManager.getRegions()) {
            if (!sb.isEmpty()) {
                sb.append("<gray>, </gray>");
            }
            sb.append("<aqua>").append(region.getId()).append("</aqua>");
        }

        note(sender, regionManager.size() == 0 ? "No regions in this world." : "Regions: " + sb);
    }

    @Command("uworldguard|uwg|worldguard|wg info <id>")
    @CommandDescription("Show details about a region")
    @Permission("uworldguard.region.info")
    public void info(final Source sender, @Argument("id") final String id) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        final ProtectedRegion parent = region.getParent();
        note(sender, "Region <aqua>" + region.getId() + "</aqua> (" + region.getType().name().toLowerCase()
            + "), priority " + region.getPriority()
            + (parent != null ? ", parent <aqua>" + parent.getId() + "</aqua>" : "")
            + ", owners " + region.getOwners().size()
            + ", members " + region.getMembers().size()
            + ", flags " + region.getFlags().size() + ".");
    }

    @Command("uworldguard|uwg|worldguard|wg flag <id> <flag> [value]")
    @CommandDescription("Set or clear a flag on a region")
    @Permission("uworldguard.region.flag")
    public void flag(
        final Source sender,
        @Argument("id") final String id,
        @Argument(value = "flag", suggestions = "flags") final String flagName,
        @Argument(value = "value", suggestions = "flag-values") @Greedy final @Nullable String value
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        final Flag<?> flag = Flags.get(flagName);
        if (flag == null) {
            error(sender, "Unknown flag <aqua>" + flagName + "</aqua>.");
            return;
        }

        if (value == null) {
            region.setFlag(flag, null);
            regionManager.markDirty();
            success(sender, "Cleared flag <aqua>" + flag.getName() + "</aqua>.");
            return;
        }

        if (!applyFlag(region, flag, value)) {
            error(sender, "Invalid value for flag <aqua>" + flag.getName() + "</aqua>.");
            return;
        }

        regionManager.markDirty();
        success(sender, "Set flag <aqua>" + flag.getName() + "</aqua> to <aqua>" + value + "</aqua>.");
    }

    @Command("uworldguard|uwg|worldguard|wg priority <id> <priority>")
    @CommandDescription("Set a region's priority")
    @Permission("uworldguard.region.priority")
    public void priority(
        final Source sender,
        @Argument("id") final String id,
        @Argument("priority") final int priority
    ) {
        final RegionManager regionManager = managerFor(sender);
        if (regionManager == null) return;

        final ProtectedRegion region = regionManager.getRegion(id);
        if (region == null) {
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        region.setPriority(priority);
        regionManager.markDirty();
        success(sender, "Set priority of <aqua>" + id + "</aqua> to <aqua>" + priority + "</aqua>.");
    }

    @Command("uworldguard|uwg|worldguard|wg setparent <id> [parent]")
    @CommandDescription("Set or clear a region's parent")
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
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        if (parentId == null) {
            region.setParent(null);
            regionManager.markDirty();
            success(sender, "Cleared the parent of <aqua>" + id + "</aqua>.");
            return;
        }

        final ProtectedRegion parent = regionManager.getRegion(parentId);
        if (parent == null) {
            error(sender, "No region named <aqua>" + parentId + "</aqua>.");
            return;
        }

        try {
            region.setParent(parent);
        } catch (final IllegalArgumentException _) {
            error(sender, "That would create a circular parent relationship.");
            return;
        }

        regionManager.markDirty();
        success(sender, "Set parent of <aqua>" + id + "</aqua> to <aqua>" + parentId + "</aqua>.");
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
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        if (region.getParent() == null) {
            error(sender, "Region <aqua>" + id + "</aqua> has no parent.");
            return;
        }

        region.setParent(null);
        regionManager.markDirty();
        success(sender, "Cleared the parent of <aqua>" + id + "</aqua>.");
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
        plugin.reloadConfig();
        success(sender, "Reloaded messages and config. <gray>(Storage/wand changes need a restart.)");
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
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        new FlagMenu(regionManager, region, chatInput).open(player);
    }

    @Command("uworldguard|uwg|worldguard|wg addowner <id> <player>")
    @CommandDescription("Add an owner to a region")
    @Permission("uworldguard.region.members")
    public void addOwner(
        final Source sender,
        @Argument("id") final String id,
        @Argument("player") final String playerName
    ) {
        member(sender, id, playerName, true, true);
    }

    @Command("uworldguard|uwg|worldguard|wg removeowner <id> <player>")
    @CommandDescription("Remove an owner from a region")
    @Permission("uworldguard.region.members")
    public void removeOwner(
        final Source sender,
        @Argument("id") final String id,
        @Argument("player") final String playerName
    ) {
        member(sender, id, playerName, true, false);
    }

    @Command("uworldguard|uwg|worldguard|wg addmember <id> <player>")
    @CommandDescription("Add a member to a region")
    @Permission("uworldguard.region.members")
    public void addMember(
        final Source sender,
        @Argument("id") final String id,
        @Argument("player") final String playerName
    ) {
        member(sender, id, playerName, false, true);
    }

    @Command("uworldguard|uwg|worldguard|wg removemember <id> <player>")
    @CommandDescription("Remove a member from a region")
    @Permission("uworldguard.region.members")
    public void removeMember(
        final Source sender,
        @Argument("id") final String id,
        @Argument("player") final String playerName
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
            error(sender, "No region named <aqua>" + id + "</aqua>.");
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            final OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            final UUID uuid = target.getUniqueId();
            final DefaultDomain domain = owner ? region.getOwners() : region.getMembers();
            if (add) {
                domain.addPlayer(uuid);
            } else {
                domain.removePlayer(uuid);
            }

            regionManager.markDirty();
            success(sender, (add ? "Added " : "Removed ") + "<aqua>" + playerName + "</aqua> "
                + (add ? "to" : "from") + " " + (owner ? "owners" : "members") + " of <aqua>" + id + "</aqua>.");
        });
    }

    @Suggestions("region-ids")
    public List<String> suggestRegionIds(final CommandContext<Source> ctx, final String input) {
        if (!(ctx.sender().source() instanceof Player player)) return List.of();

        final RegionManager regionManager = container.get(player.getWorld());
        if (regionManager == null) return List.of();

        final List<String> ids = new ArrayList<>();
        for (final ProtectedRegion region : regionManager.getRegions()) {
            ids.add(region.getId());
        }
        return ids;
    }

    @Suggestions("flags")
    public List<String> suggestFlags(final CommandContext<Source> ctx, final String input) {
        final List<String> names = new ArrayList<>(Flags.all().size());
        for (final Flag<?> flag : Flags.all()) {
            names.add(flag.getName());
        }
        return names;
    }

    @Suggestions("flag-values")
    public List<String> suggestFlagValues(final CommandContext<Source> ctx, final String input) {
        final Flag<?> flag = Flags.get(ctx.getOrDefault("flag", ""));
        if (flag instanceof StateFlag) return List.of("allow", "deny");
        if (flag instanceof BooleanFlag) return List.of("true", "false");
        if (flag == Flags.GAME_MODE) return List.of("survival", "creative", "adventure", "spectator");

        return List.of();
    }

    private static <T> boolean applyFlag(final ProtectedRegion region, final Flag<T> flag, final String value) {
        final T parsed = flag.parse(value);
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

    private static void error(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<red>" + message));
    }

    private static void success(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<green>" + message));
    }

    private static void note(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<gray>" + message));
    }
}
