package com.tricrotism.uworldguard.selection;

import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.util.BlockVector3;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    private static final String NODE = "uworldguard.region.define";

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
        if (!player.hasPermission(NODE)) {
            return;
        }
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

    /**
     * Drops a player's corners on quit. Both are {@link Location}s, which hold a strong reference to
     * their {@link org.bukkit.World} — left behind they would grow by two entries per player who
     * ever touched the wand, and keep an unloaded world reachable along with them.
     */
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        first.remove(uuid);
        second.remove(uuid);
    }

    /**
     * Drops every corner that points into {@code world}, so an unloaded world is not kept reachable by
     * the selection of someone who is still online and never made another one.
     */
    public void forgetWorld(final World world) {
        first.values().removeIf(location -> location.getWorld() == world);
        second.values().removeIf(location -> location.getWorld() == world);
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

    @Override
    public void setSelection(final Player player, final Selection selection) {
        final UUID uuid = player.getUniqueId();
        first.put(uuid, corner(selection.world(), selection.min()));
        second.put(uuid, corner(selection.world(), selection.max()));
    }

    private static Location corner(final World world, final BlockVector3 point) {
        return new Location(world, point.x(), point.y(), point.z());
    }
}
