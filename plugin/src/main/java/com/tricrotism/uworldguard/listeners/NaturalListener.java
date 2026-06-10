package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.jspecify.annotations.NullMarked;

/**
 * Enforces world/environment flags that have no associated player: fluid flow, fire
 * spread/ignition, ice and snow formation/melting, leaf decay, and crop/vine growth.
 *
 * <p>All of these resolve a {@link StateFlag} at the affected block with no membership
 * check — they describe what the world itself is allowed to do inside a region.
 */
@NullMarked
public final class NaturalListener implements Listener {

    private final RegionQuery query;

    public NaturalListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        final Material type = event.getBlock().getType();
        final StateFlag flag = switch (type) {
            case LAVA -> Flags.LAVA_FLOW;
            case WATER -> Flags.WATER_FLOW;
            default -> null;
        };
        if (flag != null && !query.testState(event.getToBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        final StateFlag flag = switch (event.getCause()) {
            case LAVA -> Flags.LAVA_FIRE;
            case FLINT_AND_STEEL -> Flags.LIGHTER;
            case SPREAD -> Flags.FIRE_SPREAD;
            default -> null;
        };
        if (flag != null && !query.testState(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE
            && !query.testState(event.getBlock().getLocation(), Flags.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        final BlockState newState = event.getNewState();
        final StateFlag flag = switch (newState.getType()) {
            case ICE, FROSTED_ICE, PACKED_ICE, BLUE_ICE -> Flags.ICE_FORM;
            case SNOW, SNOW_BLOCK -> Flags.SNOW_FALL;
            default -> null;
        };
        if (flag != null && !query.testState(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        final StateFlag flag = switch (event.getBlock().getType()) {
            case ICE, FROSTED_ICE -> Flags.ICE_MELT;
            case SNOW, SNOW_BLOCK -> Flags.SNOW_MELT;
            default -> null;
        };
        if (flag != null && !query.testState(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeafDecay(final LeavesDecayEvent event) {
        if (!query.testState(event.getBlock().getLocation(), Flags.LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        final Material type = event.getNewState().getType();
        final StateFlag flag = isVine(type) ? Flags.VINE_GROWTH : Flags.CROP_GROWTH;
        if (!query.testState(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    private static boolean isVine(final Material type) {
        return switch (type) {
            case VINE, CAVE_VINES, CAVE_VINES_PLANT, WEEPING_VINES, WEEPING_VINES_PLANT,
                 TWISTING_VINES, TWISTING_VINES_PLANT, KELP, KELP_PLANT -> true;
            default -> false;
        };
    }
}
