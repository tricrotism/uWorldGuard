package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import io.papermc.paper.event.block.VaultChangeStateEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
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
import org.bukkit.inventory.Inventory;
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
     * <p>With the flag unset the border still holds: a hopper may not pull from or push into a region
     * it does not stand in, as in WorldGuard. The end that moves the item answers for the other one,
     * like a dispenser for its target.
     *
     * <p>This is the hottest event the plugin listens to, it fires for every item every hopper moves,
     * and {@code getLocation()} allocates. So the work is laddered:
     * <ul>
     *   <li>no region sets the flag and no world holds a claim: two array walks, nothing allocated;</li>
     *   <li>the end being acted on is outside every region: one location and one lookup;</li>
     *   <li>both ends inside the same regions: two locations, two lookups and an identity test, since
     *       positions the same regions contain share one set.</li>
     * </ul>
     *
     * <p>A transfer never crosses worlds, so one end's world answers the per-world flag test and the
     * event gate for both. {@code InventoryMoveItemEvent} is not an inventory event, so the gate is
     * consulted by world and name here.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperTransfer(final InventoryMoveItemEvent event) {
        final boolean flaggedAnywhere = container.anyRegionUses(Flags.HOPPER_TRANSFER);
        if (!flaggedAnywhere && !container.anyClaims()) {
            return;
        }
        final Inventory initiator = event.getInitiator();
        final Inventory destination = event.getDestination();
        final boolean pulling = initiator == destination || initiator.equals(destination);
        final Location targetAt = (pulling ? event.getSource() : destination).getLocation();
        final Location known = targetAt != null ? targetAt : initiator.getLocation();
        if (known == null) {
            return;
        }
        final World world = known.getWorld();
        if (EventGate.disabled(world, HOPPER_EVENT)) {
            return;
        }
        final boolean flagged = flaggedAnywhere && query.usesFlag(world, Flags.HOPPER_TRANSFER);
        final ApplicableRegionSet first = query.getApplicableRegions(known);
        if (flagged && !first.testState(Flags.HOPPER_TRANSFER)) {
            event.setCancelled(true);
            return;
        }
        if (targetAt == null || !flagged && first.size() == 0) {
            return;
        }
        final Location actorAt = initiator.getLocation();
        if (actorAt == null) {
            return;
        }
        final ApplicableRegionSet atActor = query.getApplicableRegions(actorAt);
        if (atActor == first) {
            return;
        }
        if (flagged && !atActor.testState(Flags.HOPPER_TRANSFER)
            || !machineMayAct(atActor, first, Flags.HOPPER_TRANSFER)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether a machine standing in {@code atSource} may act on a position in {@code atTarget}. An
     * explicit {@code flag} there decides outright. Unset, the machine may not reach into a region it
     * does not stand in, so a claim protected only by membership is safe from machinery beside it.
     */
    static boolean machineMayAct(
        final ApplicableRegionSet atSource,
        final ApplicableRegionSet atTarget,
        final StateFlag flag
    ) {
        if (atSource == atTarget) {
            return true;
        }
        final State explicit = atTarget.queryExplicitState(flag, null);
        return explicit != State.DENY && (explicit == State.ALLOW || !entersForeignRegion(atSource, atTarget));
    }

    private static final String HOPPER_EVENT = InventoryMoveItemEvent.class.getSimpleName();

    /**
     * A dispenser answers for where it stands, and — when what it dispenses becomes a block — for
     * where that block lands. A dispenser one block outside a region and aimed into it fills the
     * region with water or lava without a {@code BlockPlaceEvent} of anyone's, so the target is
     * tested too. Only items that act on that block get that far ({@link #actsOnTarget}): everything
     * else either leaves the world alone or arrives through an event of its own (a fire charge as
     * {@code BlockIgniteEvent}).
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
        final boolean reaches = actsOnTarget(event.getItem().getType());
        if (!reaches && !query.usesFlag(world, Flags.DISPENSE)) {
            return;
        }
        final ApplicableRegionSet atDispenser = query.getApplicableRegions(block);
        if (!atDispenser.testState(Flags.DISPENSE)) {
            event.setCancelled(true);
            return;
        }
        if (!reaches || !(block.getBlockData() instanceof Directional directional)) {
            return;
        }
        final BlockFace facing = directional.getFacing();
        final ApplicableRegionSet atTarget = query.getApplicableRegions(world,
            block.getX() + facing.getModX(), block.getY() + facing.getModY(), block.getZ() + facing.getModZ());
        if (!machineMayAct(atDispenser, atTarget, Flags.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether {@code atTarget} holds a region {@code atSource} does not, skipping passthrough regions
     * since they protect nothing. A shared domain name opens the target, as for pistons.
     */
    static boolean entersForeignRegion(
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

    /**
     * Items a dispenser uses on the block in front of it with no event of their own to judge there:
     * a bucket filling or draining it, bone meal, a lighter, TNT primed in place, a shulker box set down.
     */
    private static boolean actsOnTarget(final Material item) {
        return switch (item) {
            case WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET, BUCKET, BONE_MEAL, FLINT_AND_STEEL, TNT -> true;
            default -> Tag.SHULKER_BOXES.isTagged(item);
        };
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
            || set.flagSetContains(Flags.ALLOW_INTERACT, Material.VAULT)) {
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
