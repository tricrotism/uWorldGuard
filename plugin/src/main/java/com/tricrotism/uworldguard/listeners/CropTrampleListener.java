package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
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

/**
 * Enforces the {@code crop-trample} flag — stepping on farmland reverts it to dirt and destroys
 * the crop on top. Players trample via a PHYSICAL interaction; mobs via {@link EntityInteractEvent}.
 */
@NullMarked
public final class CropTrampleListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;

    public CropTrampleListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTrample(final PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.FARMLAND) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        if (!query.testState(block.getLocation(), Flags.CROP_TRAMPLE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTrample(final EntityInteractEvent event) {
        final Block block = event.getBlock();
        if (block == null || block.getType() != Material.FARMLAND) {
            return;
        }
        if (!query.testState(block.getLocation(), Flags.CROP_TRAMPLE)) {
            event.setCancelled(true);
        }
    }
}
