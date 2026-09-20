package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which flag owns a right-click on a given block, for the handlers that would otherwise talk past
 * each other.
 *
 * <p>Five listeners answer {@code PlayerInteractEvent}, all at {@code HIGH} and all
 * {@code ignoreCancelled}, so Bukkit runs them in registration order and the first to cancel ends
 * it. {@link BuildProtectionListener} registers first and asks the broadest question, {@code
 * interact}/{@code use}, which meant a region denying those cancelled before {@code chest-access},
 * {@code permit-workbenches}, {@code use-anvil} or {@code respawn-anchors} were ever consulted. Their
 * allow was unreachable: the narrower flag could only ever tighten, never permit.
 *
 * <p>So the broad handler asks here first, and stands down where a narrower flag has been set to
 * allow explicitly. Only an explicit allow counts — an unset flag leaves {@code interact} in charge,
 * which is what keeps a region that denies interact still denying everything nobody spoke up for.
 */
@NullMarked final class InteractFlags {

    /**
     * Work stations {@code permit-workbenches} governs. Shared with {@link WorkbenchListener}, which
     * enforces them: the two have to agree on the list or a block belongs to one and not the other.
     */
    static final Set<Material> WORKBENCHES = EnumSet.of(
        Material.CRAFTING_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
        Material.ENDER_CHEST, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.LOOM,
        Material.CARTOGRAPHY_TABLE, Material.STONECUTTER, Material.ENCHANTING_TABLE);

    private InteractFlags() {
    }

    /**
     * Whether a flag narrower than {@code interact}/{@code use} explicitly allows this block.
     *
     * <p>Only called once the broad check has already decided to deny, so the common case of an
     * allowed interaction never reaches the block-state read that identifies a container.
     */
    static boolean explicitlyAllowed(final ApplicableRegionSet set, final UUID uuid, final Block block) {
        final Material type = block.getType();
        if (type == Material.RESPAWN_ANCHOR) {
            return set.queryExplicitState(Flags.RESPAWN_ANCHORS, uuid) == State.ALLOW;
        }
        if (WORKBENCHES.contains(type)) {
            return set.queryExplicitState(Flags.PERMIT_WORKBENCHES, uuid) == State.ALLOW
                || set.queryExplicitState(Flags.USE_ANVIL, uuid) == State.ALLOW;
        }
        return block.getState(false) instanceof Container
            && set.queryExplicitState(Flags.CHEST_ACCESS, uuid) == State.ALLOW;
    }
}
