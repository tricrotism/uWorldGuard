package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Enforces the three stepped-on flags. {@code crop-trample} covers farmland reverting to dirt and
 * losing the crop on top, {@code egg-trample} covers turtle and sniffer eggs, and
 * {@code use-dripleaf} rides the same events.
 *
 * <p>A player usually arrives as a PHYSICAL interaction and a mob as an
 * {@link EntityInteractEvent}, but which one fires depends on the block. Both handlers therefore
 * judge against whoever stepped whenever the event names them.
 */
@NullMarked
public final class CropTrampleListener implements Listener {


    private final RegionQuery query;

    public CropTrampleListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTrample(final PlayerInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final Player player = event.getPlayer();
        final Material type = block.getType();
        if (type == Material.BIG_DRIPLEAF) {
            if (query.usesFlag(block.getWorld(), Flags.USE_DRIPLEAF)
                && !query.testState(block, Flags.USE_DRIPLEAF, player) && !Bypass.has(player)) {
                event.setCancelled(true);
            }
            return;
        }
        final StateFlag flag = trampleFlag(type);
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        if (!query.testState(block, flag, player)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTrample(final EntityInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (block == null) {
            return;
        }
        final StateFlag flag = trampleFlag(block.getType());
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        final Player stepping = event.getEntity() instanceof Player player ? player : null;
        if (!query.testState(block, flag, stepping) && (stepping == null || !Bypass.has(stepping))) {
            event.setCancelled(true);
        }
    }

    private static @Nullable StateFlag trampleFlag(final Material type) {
        return switch (type) {
            case FARMLAND -> Flags.CROP_TRAMPLE;
            case TURTLE_EGG, SNIFFER_EGG -> Flags.EGG_TRAMPLE;
            default -> null;
        };
    }
}
