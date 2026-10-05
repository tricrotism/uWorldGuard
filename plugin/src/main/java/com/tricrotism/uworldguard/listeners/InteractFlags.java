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

    /**
     * Blocks that do something when right-clicked. Paper deprecates {@code isInteractable} as not
     * comprehensive. It errs toward including blocks like stairs, which only costs a denied block use,
     * and what items do to blocks is covered by {@link #CHANGES_BLOCK}.
     */
    private static final Set<Material> REACTS_TO_CLICK = reactsToClick();

    /**
     * Items whose right-click changes the clicked block itself: tilling, stripping, paths, waxing,
     * carving, bone meal, lighting, brushing, filling an end portal frame, mud from water, and setting
     * a spawner's mob.
     */
    private static final Set<Material> CHANGES_BLOCK = changesBlock();

    private InteractFlags() {
    }

    /**
     * Whether right-clicking {@code block} with {@code item} uses the block at all. Clicking stone to
     * place a block or to throw a wind charge does not, so as in WorldGuard no flag is consulted.
     */
    static boolean usesBlock(final Material block, final Material item) {
        return REACTS_TO_CLICK.contains(block) || CHANGES_BLOCK.contains(item);
    }

    /**
     * Whether a denied click must stop the item as well as the block. Only an item that would change
     * the block, a hoe on dirt for example. Anything else is still used, so a bottle thrown at a
     * protected door leaves the hand and a block can be placed against it.
     */
    static boolean changesBlock(final Material item) {
        return CHANGES_BLOCK.contains(item);
    }

    @SuppressWarnings("deprecation")
    private static Set<Material> reactsToClick() {
        final Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (final Material material : Material.values()) {
            if (!material.isLegacy() && material.isBlock() && material.isInteractable()) {
                blocks.add(material);
            }
        }
        return blocks;
    }

    private static Set<Material> changesBlock() {
        final Set<Material> items = EnumSet.of(Material.BONE_MEAL, Material.FLINT_AND_STEEL,
            Material.FIRE_CHARGE, Material.SHEARS, Material.HONEYCOMB, Material.BRUSH, Material.ENDER_EYE,
            Material.POTION);
        for (final Material material : Material.values()) {
            final String name = material.name();
            if (!material.isLegacy() && (name.endsWith("_HOE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_SPAWN_EGG"))) {
                items.add(material);
            }
        }
        return items;
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
            if (set.queryExplicitState(Flags.PERMIT_WORKBENCHES, uuid) == State.ALLOW
                || set.queryExplicitState(Flags.USE_ANVIL, uuid) == State.ALLOW) {
                return true;
            }
            // An ender chest holds only the opener's own items, so membership has nothing to guard.
            return type == Material.ENDER_CHEST
                && set.queryExplicitState(Flags.INTERACT, uuid) != State.DENY
                && set.queryExplicitState(Flags.USE, uuid) != State.DENY;
        }
        return set.queryExplicitState(Flags.CHEST_ACCESS, uuid) == State.ALLOW
            && block.getState(false) instanceof Container;
    }
}
