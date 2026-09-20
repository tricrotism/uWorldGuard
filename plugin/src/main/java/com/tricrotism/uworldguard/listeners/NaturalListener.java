package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;

/**
 * Enforces world/environment flags that have no associated player: fluid flow, fire
 * spread/ignition, ice and snow formation/melting, leaf decay, crop/vine/mushroom/sculk/rock
 * growth, grass and mycelium spread, coral and copper fading, lava setting into stone, and farmland
 * moisture.
 *
 * <p>All of these resolve a {@link StateFlag} at the affected block with no membership
 * check — they describe what the world itself is allowed to do inside a region.
 *
 * <p>Every handler tests {@link RegionQuery#usesFlag} before resolving anything. These are the
 * hottest events the server fires — fluid flow alone runs thousands of times a second on any world
 * with an ocean — and each flag here defaults to allow, so a world where nobody set it has the
 * handler answering "carry on" after one bitset test rather than a chunk hash, a candidate scan and
 * two allocations per block.
 */
@NullMarked
public final class NaturalListener implements Listener {

    private static final Set<Material> CORALS = fadeable("CORAL");
    private static final Set<Material> COPPERS = fadeable("COPPER");

    private final RegionQuery query;

    public NaturalListener(final RegionQuery query) {
        this.query = query;
    }

    private static Set<Material> fadeable(final String token) {
        final EnumSet<Material> matching = EnumSet.noneOf(Material.class);
        for (final Material material : Material.values()) {
            if (material.name().contains(token)) {
                matching.add(material);
            }
        }
        return matching;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block from = event.getBlock();
        final StateFlag flag = switch (from.getType()) {
            case LAVA -> Flags.LAVA_FLOW;
            case WATER -> Flags.WATER_FLOW;
            default -> null;
        };
        if (flag == null || !query.usesFlag(from.getWorld(), flag)) {
            return;
        }
        if (!query.testState(event.getToBlock(), flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final StateFlag flag = switch (event.getCause()) {
            case LAVA -> Flags.LAVA_FIRE;
            case FLINT_AND_STEEL, FIREBALL, ARROW -> Flags.LIGHTER;
            case SPREAD -> Flags.FIRE_SPREAD;
            default -> null;
        };
        final Block block = event.getBlock();
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        if (!query.testState(block, flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Block-to-block spread: fire, and the surface/growth spreads that share this event — grass and
     * mycelium creeping onto neighbouring dirt, sculk blooming from a catalyst, mushrooms multiplying.
     * The flag is chosen from the source block except for mushrooms, which spread from one mushroom to
     * an empty neighbour and so are identified by what is appearing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final StateFlag flag = switch (event.getSource().getType()) {
            case FIRE -> Flags.FIRE_SPREAD;
            case GRASS_BLOCK -> Flags.GRASS_GROWTH;
            case MYCELIUM -> Flags.MYCELIUM_SPREAD;
            case SCULK, SCULK_CATALYST, SCULK_VEIN -> Flags.SCULK_GROWTH;
            case RED_MUSHROOM, BROWN_MUSHROOM -> Flags.MUSHROOM_GROWTH;
            default -> null;
        };
        final Block block = event.getBlock();
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        if (!query.testState(block, flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }

        final Block block = event.getBlock();
        if (event instanceof EntityBlockFormEvent entityForm) {
            final Entity former = entityForm.getEntity();
            if (former instanceof Player walker) {
                if (query.usesFlag(block.getWorld(), Flags.FROSTWALKER)
                    && !query.testState(block, Flags.FROSTWALKER, walker)) {
                    event.setCancelled(true);
                }
                return;
            }

            if (former instanceof Snowman) {
                if (query.usesFlag(block.getWorld(), Flags.SNOWMAN_TRAILS)
                    && !query.testState(block, Flags.SNOWMAN_TRAILS)) {
                    event.setCancelled(true);
                }
                return;
            }
        }
        final StateFlag flag = switch (event.getNewState().getType()) {
            case ICE, FROSTED_ICE, PACKED_ICE, BLUE_ICE -> Flags.ICE_FORM;
            case SNOW, SNOW_BLOCK -> Flags.SNOW_FALL;
            case POINTED_DRIPSTONE, DRIPSTONE_BLOCK -> Flags.ROCK_GROWTH;
            case OBSIDIAN, COBBLESTONE, STONE, BASALT -> Flags.LAVA_HARDEN;
            default -> null;
        };
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        if (!query.testState(block, flag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        final Material type = block.getType();
        StateFlag flag = switch (type) {
            case ICE -> Flags.ICE_MELT;
            case FROSTED_ICE -> Flags.FROSTED_ICE_MELT;
            case SNOW, SNOW_BLOCK -> Flags.SNOW_MELT;
            default -> null;
        };

        if (flag == null) {
            if (CORALS.contains(type)) {
                flag = Flags.CORAL_FADE;
            } else if (COPPERS.contains(type)) {
                flag = Flags.COPPER_FADE;
            }
        }
        if (flag == null || !query.usesFlag(block.getWorld(), flag)) {
            return;
        }
        if (!query.testState(block, flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Farmland hydration. Drying out is split onto its own flag because that is the destructive
     * direction — dry farmland reverts to dirt and breaks the crop above it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMoistureChange(final MoistureChangeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (!query.usesFlag(block.getWorld(), Flags.MOISTURE_CHANGE)
            && !query.usesFlag(block.getWorld(), Flags.SOIL_DRY)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!set.testState(Flags.MOISTURE_CHANGE)) {
            event.setCancelled(true);
            return;
        }

        if (set.worldUses(Flags.SOIL_DRY) && !set.testState(Flags.SOIL_DRY)
            && isDrying(block, event.getNewState())) {
            event.setCancelled(true);
        }
    }

    private static boolean isDrying(final Block block, final BlockState newState) {
        return block.getBlockData() instanceof Farmland before
            && newState.getBlockData() instanceof Farmland after
            && after.getMoisture() < before.getMoisture();
    }

    /**
     * Fire consuming a block. {@code fire-spread} previously only stopped fire from travelling
     * ({@code BlockIgniteEvent} / {@code BlockSpreadEvent}) — the block actually burning away is a
     * separate event, so fire lit inside a region, or by lava, still destroyed it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.FIRE_SPREAD)
            && !query.testState(block, Flags.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    /**
     * A sculk catalyst charging its surroundings. This is the event sculk actually blooms through;
     * the {@code BlockSpreadEvent} case above only catches vein creep, so without this
     * {@code sculk-growth} would half-work.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSculkBloom(final SculkBloomEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.SCULK_GROWTH)
            && !query.testState(block, Flags.SCULK_GROWTH)) {
            event.setCancelled(true);
        }
    }

    /**
     * Explosions with no entity behind them — a bed or respawn anchor detonating in the wrong
     * dimension. {@code EntityExplodeEvent} never fires for these, so they were ungated entirely.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.blockList().isEmpty()
            || !query.usesFlag(event.getBlock().getWorld(), Flags.OTHER_EXPLOSION)) {
            return;
        }
        event.blockList().removeIf(block -> !query.testState(block, Flags.OTHER_EXPLOSION));
    }

    /**
     * A sapling or mushroom maturing. This is the one growth that expands well beyond the block it
     * started on, so a tree planted just outside a region can otherwise push its canopy straight
     * through the border. Each resulting block is filtered on its own; the growth is only refused
     * outright when nothing it would place is allowed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.usesFlag(event.getWorld(), Flags.TREE_GROWTH)) {
            return;
        }
        event.getBlocks().removeIf(state -> !query.testState(state.getBlock(), Flags.TREE_GROWTH));
        if (event.getBlocks().isEmpty()) {
            event.setCancelled(true);
        }
    }

    /**
     * Lighting a new portal frame. Separate from {@code nether-portals}, which governs travelling
     * through one that already exists.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(final PortalCreateEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.usesFlag(event.getWorld(), Flags.PORTAL_CREATE)) {
            return;
        }
        for (final BlockState state : event.getBlocks()) {
            if (!query.testState(state.getBlock(), Flags.PORTAL_CREATE)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeafDecay(final LeavesDecayEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.LEAF_DECAY)
            && !query.testState(block, Flags.LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Material type = event.getNewState().getType();
        final StateFlag flag = switch (type) {
            case POINTED_DRIPSTONE, DRIPSTONE_BLOCK -> Flags.ROCK_GROWTH;
            default -> isVine(type) ? Flags.VINE_GROWTH : Flags.CROP_GROWTH;
        };
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), flag) && !query.testState(block, flag)) {
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
