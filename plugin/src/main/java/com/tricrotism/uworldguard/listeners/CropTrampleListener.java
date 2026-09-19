package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
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
 * Enforces {@code crop-trample} — stepping on farmland reverts it to dirt and destroys the crop on
 * top — and {@code use-dripleaf}, which shares the same stepped-on events. Players trigger both via a
 * PHYSICAL interaction; mobs via {@link EntityInteractEvent}.
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
        if (type != Material.FARMLAND || !query.usesFlag(block.getWorld(), Flags.CROP_TRAMPLE)) {
            return;
        }
        if (!query.testState(block, Flags.CROP_TRAMPLE, player)) {
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
        if (block == null || block.getType() != Material.FARMLAND
            || !query.usesFlag(block.getWorld(), Flags.CROP_TRAMPLE)) {
            return;
        }
        if (!query.testState(block, Flags.CROP_TRAMPLE)) {
            event.setCancelled(true);
        }
    }
}
