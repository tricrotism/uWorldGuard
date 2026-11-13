package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Enforces the pistons flag and the region border. Without it a piston placed outside a region can
 * push blocks in, or pull blocks out, entirely bypassing block-place / block-break — the classic
 * border grief.
 *
 * <p>Both the piston itself and every block it moves are checked: on extend against the position each
 * block is pushed into, on retract against the position each block is pulled from. Coordinates are
 * offset arithmetically rather than via {@code getRelative}, which would allocate a {@link Block} per
 * moved block.
 *
 * <p>With the flag unset a piston still may not move blocks of a region it does not stand in, as in
 * WorldGuard. A position inside the piston's own regions resolves to the same shared set, so the
 * usual push is an identity test per block.
 *
 * <p>In a straight push each block's destination is the next block's source, so the position last
 * checked is remembered and that repeat is skipped: a ten-block push costs eleven queries rather
 * than twenty.
 *
 * <p>{@code nonplayer-protection-domains} is how machinery spans regions. A position that names a
 * domain the piston's own regions also name counts as open to that piston, so a redstone room and
 * the farm it drives need not be one region. Reading the piston's domains costs one pass per event,
 * and returns nothing at all on a world where nobody set the flag.
 */
@NullMarked
public final class PistonListener implements Listener {

    private final RegionQuery query;

    public PistonListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExtend(final BlockPistonExtendEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final BlockFace direction = event.getDirection();
        if (denied(event.getBlock(), event.getBlocks(),
            direction.getModX(), direction.getModY(), direction.getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRetract(final BlockPistonRetractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (denied(event.getBlock(), event.getBlocks(), 0, 0, 0)) {
            event.setCancelled(true);
        }
    }

    private boolean denied(
        final Block piston, final List<Block> moved, final int dx, final int dy, final int dz
    ) {
        final World world = piston.getWorld();
        final boolean flagged = query.usesFlag(world, Flags.PISTONS);
        final ApplicableRegionSet atPiston = query.getApplicableRegions(piston);
        if (flagged && !atPiston.testState(Flags.PISTONS)) {
            return true;
        }
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 0, n = moved.size(); i < n; i++) {
            final Block block = moved.get(i);
            final int x = block.getX();
            final int y = block.getY();
            final int z = block.getZ();
            if ((x != lastX || y != lastY || z != lastZ)
                && !allowed(query.getApplicableRegions(world, x, y, z), atPiston, flagged)) {
                return true;
            }
            if ((dx | dy | dz) != 0) {
                lastX = x + dx;
                lastY = y + dy;
                lastZ = z + dz;
                if (!allowed(query.getApplicableRegions(world, lastX, lastY, lastZ), atPiston, flagged)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the piston may act on this position. A position that sets {@code pistons} decides, with
     * a deny lifted by a domain the piston's own regions also name. Unset, the position must not
     * belong to a region the piston stands outside of.
     */
    private static boolean allowed(
        final ApplicableRegionSet at, final ApplicableRegionSet atPiston, final boolean flagged
    ) {
        if (at == atPiston) {
            return true;
        }
        final State explicit = flagged ? at.queryExplicitState(Flags.PISTONS, null) : null;
        if (explicit == State.ALLOW) {
            return true;
        }
        if (explicit == State.DENY) {
            return at.flagSetIntersects(Flags.NONPLAYER_PROTECTION_DOMAINS,
                atPiston.flagSetUnion(Flags.NONPLAYER_PROTECTION_DOMAINS));
        }
        return !MachineListener.entersForeignRegion(atPiston, at);
    }
}
