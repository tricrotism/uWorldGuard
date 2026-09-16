package com.tricrotism.uworldguard.listeners;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.sk89q.worldguard.bukkit.util.Events;
import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.config.InteractionWhitelist;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.MaterialSetFlag;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Enforces the build, block-break, block-place, interact, use, and pvp flags — including the two
 * paths that bypass {@code BlockPlaceEvent} entirely, bucket fluid placement and hanging entities.
 */
@NullMarked
public final class BuildProtectionListener implements Listener {

    private final RegionQuery query;
    private final MessageService messages;

    public BuildProtectionListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final Material type = block.getType();
        if (set.flagSetContains(Flags.DENY_BLOCK_BREAK, type)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.BLOCK_BREAK, set.queryValue(Flags.DENY_MESSAGE));
            return;
        }
        if (set.flagSetContains(Flags.ALLOW_BLOCK_BREAK, type)) {
            return;
        }
        if (!set.testBuild(player.getUniqueId(), Flags.BLOCK_BREAK)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.BLOCK_BREAK, set.queryValue(Flags.DENY_MESSAGE));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final Material type = block.getType();
        if (set.flagSetContains(Flags.DENY_BLOCK_PLACE, type)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.BLOCK_PLACE, set.queryValue(Flags.DENY_MESSAGE));
            return;
        }
        if (set.flagSetContains(Flags.ALLOW_BLOCK_PLACE, type)) {
            return;
        }
        if (!set.testBuild(player.getUniqueId(), Flags.BLOCK_PLACE)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.BLOCK_PLACE, set.queryValue(Flags.DENY_MESSAGE));
        }
    }

    /**
     * Emptying or filling a bucket never fires {@link BlockPlaceEvent} or {@link BlockBreakEvent} —
     * fluid placement is its own event — so without this a non-member could pour lava into an
     * otherwise fully protected region, or drain its water, and nothing would stop them.
     *
     * <p>Emptying is judged as a place and filling as a break, matching what the player is doing to
     * the world, so the same block-place / block-break flags and material lists govern both.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (!EventGate.disabled(event)) {
            checkBucket(event, event.getBlock(), Flags.BUCKET_EMPTY, Flags.BLOCK_PLACE,
                Flags.DENY_BLOCK_PLACE, Flags.ALLOW_BLOCK_PLACE, fluidOf(event.getBucket()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (!EventGate.disabled(event)) {
            checkBucket(event, event.getBlock(), Flags.BUCKET_FILL, Flags.BLOCK_BREAK,
                Flags.DENY_BLOCK_BREAK, Flags.ALLOW_BLOCK_BREAK, fluidIn(event.getBlock()));
        }
    }

    /**
     * Shared by both bucket handlers. They cannot be one handler on {@code PlayerBucketEvent}: that
     * class declares no handler list of its own, so registering against it fails outright.
     *
     * <p>Both pass {@code event.getBlock()} — the block the server says this event changes, which is
     * where the fluid lands when emptying and the source drained when filling. It is not the clicked
     * block and needs no offset applied to it; adding one checked the block beyond the one actually
     * changing, so a pour aimed at a region's edge was judged against its neighbour.
     *
     * <p>{@code bucket} decides on its own when a region sets it, so an arena can hand out water
     * buckets with {@code bucket-empty: allow} and {@code bucket-fill: allow} without putting
     * {@code WATER} on the block lists, where it would read as permission to mine the map's ponds.
     * Only when no region sets it do the block flags and material lists decide, which keeps every
     * config written before these flags existed behaving as it did.
     *
     * <p>{@code material} is what the player is adding to or taking from the world, so the same
     * per-material deny/allow lists that govern {@link BlockPlaceEvent} and {@link BlockBreakEvent}
     * govern buckets too. Neither side reads it from the block's own type: when emptying the block is
     * still air (or the block about to be waterlogged), and when filling the block may be a waterlogged
     * slab or stair whose type is the container, not the fluid actually leaving the world.
     */
    private void checkBucket(
        final PlayerBucketEvent event, final Block block, final StateFlag bucket, final StateFlag flag,
        final MaterialSetFlag denied, final MaterialSetFlag allowed, final Material material
    ) {
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final State explicit = set.queryExplicitState(bucket, player.getUniqueId());
        if (explicit == State.ALLOW) {
            return;
        }
        if (explicit == null && !set.flagSetContains(denied, material)) {
            if (set.flagSetContains(allowed, material)) {
                return;
            }
            if (set.testBuild(player.getUniqueId(), flag)) {
                return;
            }
        }
        if (Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, explicit == State.DENY ? bucket : flag,
            set.queryValue(Flags.DENY_MESSAGE));
    }

    /**
     * The block a filled bucket puts into the world. Mob buckets carry water with them, so they
     * count as placing water.
     */
    private static Material fluidOf(final Material bucket) {
        return switch (bucket) {
            case LAVA_BUCKET -> Material.LAVA;
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
            case WATER_BUCKET, COD_BUCKET, SALMON_BUCKET, PUFFERFISH_BUCKET, TROPICAL_FISH_BUCKET,
                 AXOLOTL_BUCKET, TADPOLE_BUCKET -> Material.WATER;
            default -> Material.AIR;
        };
    }

    /**
     * The fluid a bucket takes out of {@code block}. A waterlogged slab, stair, fence or trapdoor is
     * still that block after the water is drawn out of it, so the material that leaves the world is
     * water — reading the block's own type here would judge the pickup against the container and force
     * every waterloggable block onto the break list to make buckets work.
     */
    private static Material fluidIn(final Block block) {
        final Material type = block.getType();
        if (type == Material.WATER || type == Material.LAVA || type == Material.POWDER_SNOW) {
            return type;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
            ? Material.WATER
            : type;
    }

    /**
     * Item frames and paintings are entities, so hanging them fires neither {@link BlockPlaceEvent}
     * nor any flag we already check — the destroy side was covered but the place side was not.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());
        if (set.testBuild(player.getUniqueId(), Flags.BLOCK_PLACE)) {
            return;
        }
        if (Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, Flags.BLOCK_PLACE, set.queryValue(Flags.DENY_MESSAGE));
    }

    /**
     * Armour stands and mannequins are placed as entities, so no block event covers them and they
     * are not hangings either — a non-member could decorate a fully protected region with them.
     *
     * <p>Narrow on purpose. The other entities this event carries are placed through flags of their
     * own ({@code vehicle-place}, {@code end-crystal-place}), and a spawn egg is judged by
     * {@code mob-spawning} where the mob appears.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(final EntityPlaceEvent event) {
        final Entity placed = event.getEntity();
        if (!(placed instanceof ArmorStand || placed instanceof Mannequin)) {
            return;
        }
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(placed);
        if (set.testBuild(player.getUniqueId(), Flags.BLOCK_PLACE) || Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, Flags.BLOCK_PLACE, set.queryValue(Flags.DENY_MESSAGE));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (InteractionWhitelist.allows(block.getWorld(), block.getType())) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!set.testBuild(player.getUniqueId(), Flags.INTERACT, Flags.USE)) {
            if (Bypass.has(player)) {
                return;
            }
            if (InteractFlags.explicitlyAllowed(set, player.getUniqueId(), block)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, Flags.INTERACT, set.queryValue(Flags.DENY_MESSAGE));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(final EntityDamageByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player defender)) {
            return;
        }
        final Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (!query.getApplicableRegions(defender)
            .testState(Flags.PVP, attacker.getUniqueId())) {
            if (Bypass.has(attacker)) {
                return;
            }
            if (Events.fireAndTestCancel(new DisallowedPVPEvent(attacker, defender, event))) {
                return;
            }
            event.setCancelled(true);
        }
    }

    private static @Nullable Player resolvePlayer(final Object damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
