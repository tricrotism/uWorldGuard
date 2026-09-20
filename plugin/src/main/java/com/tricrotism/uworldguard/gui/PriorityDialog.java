package com.tricrotism.uworldguard.gui;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The priority reorder as a client dialog: pick a region, pick above or below, pick the region to
 * measure it against, confirm.
 *
 * <p>Nobody knows what number a region should have, because the number that works depends on every
 * other region's and none of them are on screen. Three dropdowns ask the question people can
 * actually answer. The command form takes a whole chain at once and is still the faster tool once
 * you know the names; this is the one you can use without knowing them.
 *
 * <p>It is a form and not a drag-to-reorder list because the dialog API has no list input. The
 * inputs are boolean, number range, single option and text, and {@code DialogListType} is a menu of
 * other dialogs rather than reorderable data. Ordering a whole world visually needs the item menu.
 */
@NullMarked
public final class PriorityDialog {

    /**
     * Beyond this many regions the dropdowns stop being a usable way to find one, so the dialog
     * declines and points at the command rather than rendering a list nobody can scroll.
     */
    private static final int MAX_OPTIONS = 64;

    private static final String REGION_KEY = "region";
    private static final String PLACE_KEY = "place";
    private static final String REFERENCE_KEY = "reference";
    private static final String ABOVE = "above";

    private PriorityDialog() {}

    /**
     * Shows the dialog to {@code player}, or explains why it cannot.
     *
     * <p>{@code onConfirm} receives the two regions in the order they should end up, highest first,
     * and is called on whatever thread the client's response arrives on. Regions are resolved by id
     * inside the callback rather than captured, since the dialog stays open for as long as the player
     * leaves it open and either one can be removed in the meantime.
     */
    public static void open(
        final Player player, final RegionManager manager,
        final BiConsumer<Player, List<ProtectedRegion>> onConfirm
    ) {
        final List<ProtectedRegion> regions = new ArrayList<>(manager.getRegions());
        if (regions.size() < 2) {
            player.sendMessage(Messages.format("<red>This world needs at least two regions to order."));
            return;
        }
        if (regions.size() > MAX_OPTIONS) {
            player.sendMessage(Messages.format("<red>Too many regions here to pick from a list. Use "
                + "<aqua>/uwg priority <a>><b></aqua> instead."));
            return;
        }
        regions.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed());

        final List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            final ProtectedRegion region = regions.get(i);
            options.add(SingleOptionDialogInput.OptionEntry.create(region.getId(),
                Messages.format("<white><id> <dark_gray>(<priority>)",
                    Placeholder.unparsed("id", region.getId()),
                    Placeholder.unparsed("priority", Integer.toString(region.getPriority()))),
                i == 0));
        }

        final List<SingleOptionDialogInput.OptionEntry> places = List.of(
            SingleOptionDialogInput.OptionEntry.create(ABOVE, Messages.format("<white>above"), true),
            SingleOptionDialogInput.OptionEntry.create("below", Messages.format("<white>below"), false));

        final Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Messages.format("<dark_gray>Region priority"))
                .body(List.of(DialogBody.plainMessage(Messages.format(
                    "<gray>The region you pick wins where the two overlap."))))
                .inputs(List.of(
                    DialogInput.singleOption(REGION_KEY,
                        Messages.format("<yellow>Region"), options).build(),
                    DialogInput.singleOption(PLACE_KEY,
                        Messages.format("<yellow>Goes"), places).build(),
                    DialogInput.singleOption(REFERENCE_KEY,
                        Messages.format("<yellow>The region"), options).build()))
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(Messages.format("<green>Apply"))
                    .action(DialogAction.customClick(
                        (response, audience) -> apply(player, manager, response, onConfirm),
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                    .build(),
                ActionButton.builder(Messages.format("<red>Cancel")).build())));

        player.showDialog(dialog);
    }

    private static void apply(
        final Player player, final RegionManager manager,
        final io.papermc.paper.dialog.DialogResponseView response,
        final BiConsumer<Player, List<ProtectedRegion>> onConfirm
    ) {
        final String chosen = response.getText(REGION_KEY);
        final String against = response.getText(REFERENCE_KEY);
        final String place = response.getText(PLACE_KEY);
        if (chosen == null || against == null || place == null) {
            return;
        }
        if (chosen.equals(against)) {
            player.sendMessage(Messages.format("<red>Pick two different regions."));
            return;
        }
        final ProtectedRegion first = manager.getRegion(chosen);
        final ProtectedRegion second = manager.getRegion(against);
        if (first == null || second == null) {
            player.sendMessage(Messages.format("<red>One of those regions no longer exists."));
            return;
        }
        onConfirm.accept(player, ABOVE.equals(place) ? List.of(first, second) : List.of(second, first));
    }
}
