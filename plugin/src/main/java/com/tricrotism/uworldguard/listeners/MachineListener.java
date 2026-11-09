package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import io.papermc.paper.event.block.VaultChangeStateEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.jspecify.annotations.NullMarked;

/**
 * Automated and station-based block machinery: crafters, hoppers, dispensers, enchanting tables,
 * brewing stands, furnaces, TNT priming, sponges, and trial-chamber vaults. None of these run through
 * a player's build or interact check once placed, so each is its own way to keep acting inside a
 * region long after whoever set it up has gone.
 */
@NullMarked
public final class MachineListener implements Listener {

    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final MessageService messages;

    public MachineListener(
        final RegionContainerImpl container, final RegionQuery query, final MessageService messages
    ) {
        this.container = container;
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafterCraft(final CrafterCraftEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.CRAFTER) && !query.testState(block, Flags.CRAFTER)) {
            event.setCancelled(true);
        }
    }

    /**
     * Hopper and dropper transfers, judged at both ends. A hopper or hopper minecart just outside a
     * region, under it or beside it, pulls from a chest inside: the destination is outside, so only the
     * source sees the flag. A chain pushing into a region is caught at the destination. Either end
     * denying stops the move. The destination is tested first and the source only when it passes.
     *
     * <p>This is the hottest event the plugin listens to: it fires for every item every hopper moves.
     * The registry check comes first because {@code getLocation()} allocates a {@link Location} — on a
     * server where no region sets the flag this handler is a bitset test per world and nothing else:
     * no allocation, no region resolved.
     *
     * <p>A transfer never crosses worlds, so the destination's world answers the per-world flag test
     * and the event gate for both ends. {@code InventoryMoveItemEvent} is not an inventory event, so
     * the gate is consulted by world and name here.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperTransfer(final InventoryMoveItemEvent event) {
        if (!container.anyRegionUses(Flags.HOPPER_TRANSFER)) {
            return;
        }
        final Location destination = event.getDestination().getLocation();
        if (destination == null) {
            return;
        }
        final World world = destination.getWorld();
        if (!query.usesFlag(world, Flags.HOPPER_TRANSFER) || EventGate.disabled(world, HOPPER_EVENT)) {
            return;
        }
        if (!query.testState(destination, Flags.HOPPER_TRANSFER)) {
            event.setCancelled(true);
            return;
        }
        final Location source = event.getSource().getLocation();
        if (source != null && !query.testState(source, Flags.HOPPER_TRANSFER)) {
            event.setCancelled(true);
        }
    }

    private static final String HOPPER_EVENT = InventoryMoveItemEvent.class.getSimpleName();

    /**
     * A dispenser answers for where it stands, and — when what it dispenses becomes a block — for
     * where that block lands. A dispenser one block outside a region and aimed into it fills the
     * region with water or lava without a {@code BlockPlaceEvent} of anyone's, so the target is
     * tested too. Only the three fluid buckets get that far: everything else either leaves the world
     * alone or arrives through an event of its own (a fire charge as {@code BlockIgniteEvent}).
     *
     * <p>The target is protected the way a player's placement would be. An explicit
     * {@code block-place} decides outright. Unset, the fluid is refused when it lands in a region the
     * dispenser does not stand in, unless the two share a {@code nonplayer-protection-domains} name,
     * as pistons do. So a region protected only by membership is safe from a dispenser outside it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(final BlockDispenseEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        final World world = block.getWorld();
        final boolean fluid = isFluidBucket(event.getItem().getType());
        if (!fluid && !query.usesFlag(world, Flags.DISPENSE)) {
            return;
        }
        final ApplicableRegionSet atDispenser = query.getApplicableRegions(block);
        if (!atDispenser.testState(Flags.DISPENSE)) {
            event.setCancelled(true);
            return;
        }
        if (!fluid || !(block.getBlockData() instanceof Directional directional)) {
            return;
        }
        final BlockFace facing = directional.getFacing();
        final ApplicableRegionSet atTarget = query.getApplicableRegions(world,
            block.getX() + facing.getModX(), block.getY() + facing.getModY(), block.getZ() + facing.getModZ());
        final State explicit = atTarget.queryExplicitState(Flags.BLOCK_PLACE, null);
        if (explicit == State.DENY || (explicit == null && entersForeignRegion(atDispenser, atTarget))) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether {@code atTarget} holds a region {@code atSource} does not, skipping passthrough regions
     * since they protect nothing. A shared domain name opens the target, as for pistons.
     */
    private static boolean entersForeignRegion(
        final ApplicableRegionSet atSource, final ApplicableRegionSet atTarget
    ) {
        for (int i = 0, n = atTarget.size(); i < n; i++) {
            final ProtectedRegion region = atTarget.get(i);
            if (region.getFlag(Flags.PASSTHROUGH) == State.ALLOW || contains(atSource, region)) {
                continue;
            }
            return !atTarget.flagSetIntersects(Flags.NONPLAYER_PROTECTION_DOMAINS,
                atSource.flagSetUnion(Flags.NONPLAYER_PROTECTION_DOMAINS));
        }
        return false;
    }

    private static boolean contains(final ApplicableRegionSet set, final ProtectedRegion region) {
        final String id = region.getId();
        for (int i = 0, n = set.size(); i < n; i++) {
            if (set.get(i).getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFluidBucket(final Material item) {
        return item == Material.WATER_BUCKET || item == Material.LAVA_BUCKET
            || item == Material.POWDER_SNOW_BUCKET;
    }

    /**
     * Priming TNT — by redstone, fire, a flaming arrow, or another explosion. Distinct from
     * {@code tnt}, which governs the blast: this stops the block ever lighting.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPrime(final TNTPrimeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (!query.usesFlag(block.getWorld(), Flags.TNT_PRIME) || query.testState(block, Flags.TNT_PRIME)) {
            return;
        }
        if (event.getPrimingEntity() instanceof Player player && Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpongeAbsorb(final SpongeAbsorbEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.SPONGE_ABSORB) && !query.testState(block, Flags.SPONGE_ABSORB)) {
            event.setCancelled(true);
        }
    }

    /**
     * Opening a trial-chamber vault (1.21). The loot is one-per-player and unrecoverable, so a region
     * that wants its chambers left alone has no other way to say so.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVault(final VaultChangeStateEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getBlock());
        final Player player = event.getPlayer();
        if (player == null) {
            if (!set.testState(Flags.VAULT_USE)) {
                event.setCancelled(true);
            }
            return;
        }
        if (set.testBuild(player.getUniqueId(), Flags.VAULT_USE) || Bypass.has(player)
            || set.flagSetContains(Flags.ALLOW_INTERACT, Material.VAULT, player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, Flags.VAULT_USE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(final EnchantItemEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getEnchanter();
        if (query.getApplicableRegions(event.getEnchantBlock())
            .testState(Flags.ENCHANT, player.getUniqueId()) || Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, Flags.ENCHANT);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(final BrewEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.BREW) && !query.testState(block, Flags.BREW)) {
            event.setCancelled(true);
        }
    }

    /**
     * Covers furnaces, smokers, blast furnaces and campfires in one go — they all arrive here as
     * {@link BlockCookEvent}, of which the furnace event is a subclass.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCook(final BlockCookEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.SMELT) && !query.testState(block, Flags.SMELT)) {
            event.setCancelled(true);
        }
    }

}
