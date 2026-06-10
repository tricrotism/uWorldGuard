package com.tricrotism.uworldguard.selection;

import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.util.BlockVector3;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in selection used when WorldEdit is absent: left-click sets the first corner,
 * right-click the second, with the configured wand item.
 *
 * <p>Per-player corners are held in concurrent maps; interaction events arrive on the
 * region thread that owns the block, so no extra scheduling is needed.
 */
@NullMarked
public final class WandSelectionProvider implements SelectionProvider, Listener {

    private final Material wand;
    private final Map<UUID, Location> first = new ConcurrentHashMap<>();
    private final Map<UUID, Location> second = new ConcurrentHashMap<>();

    public WandSelectionProvider(final Material wand) {
        this.wand = wand;
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || event.getItem() == null || event.getItem().getType() != wand) {
            return;
        }
        final Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            first.put(player.getUniqueId(), block.getLocation());
            send(player, "first", block);
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            second.put(player.getUniqueId(), block.getLocation());
            send(player, "second", block);
            event.setCancelled(true);
        }
    }

    private void send(final Player player, final String which, final Block block) {
        player.sendMessage(Messages.format(
            "<gray>Set <aqua><which></aqua> position to <aqua><x>, <y>, <z></aqua>.",
            Placeholder.unparsed("which", which),
            Placeholder.unparsed("x", Integer.toString(block.getX())),
            Placeholder.unparsed("y", Integer.toString(block.getY())),
            Placeholder.unparsed("z", Integer.toString(block.getZ()))));
    }

    @Override
    public @Nullable Selection getSelection(final Player player) {
        final Location a = first.get(player.getUniqueId());
        final Location b = second.get(player.getUniqueId());
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return null;
        }
        return new Selection(a.getWorld(), BlockVector3.of(a), BlockVector3.of(b));
    }
}
