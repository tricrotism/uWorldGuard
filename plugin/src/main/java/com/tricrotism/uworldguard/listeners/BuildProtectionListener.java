package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.config.InteractionWhitelist;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.MaterialSetFlag;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.DenyDebug;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the build, block-break, block-place, interact, use, and pvp flags — including the two
 * paths that bypass {@code BlockPlaceEvent} entirely, bucket fluid placement and hanging entities.
 */
@NullMarked
public final class BuildProtectionListener implements Listener {

    /**
     * Blocks an entity triggers by touching them: every pressure plate and button, and tripwire.
     */
    private static final Set<Material> TRIGGERS = triggers();
    private static final double GHOST_CLIMB_DROP = -0.5;

    private final RegionQuery query;
    private final MessageService messages;
    private final boolean stopGhostClimb;

    public BuildProtectionListener(final RegionQuery query, final MessageService messages, final boolean stopGhostClimb) {
        this.query = query;
        this.messages = messages;
        this.stopGhostClimb = stopGhostClimb;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final Material type = set.worldUses(Flags.DENY_BLOCK_BREAK) || set.worldUses(Flags.ALLOW_BLOCK_BREAK)
            ? block.getType() : Material.AIR;
        if (set.flagSetContains(Flags.DENY_BLOCK_BREAK, type, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendRegionDeny(player, Flags.BLOCK_BREAK, set);
            return;
        }
        if (set.flagSetContains(Flags.ALLOW_BLOCK_BREAK, type, player.getUniqueId())) {
            return;
        }
        if (!set.testBuild(player.getUniqueId(), Flags.BLOCK_BREAK)) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendRegionDeny(player, Flags.BLOCK_BREAK, set);
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
        final Material type = set.worldUses(Flags.DENY_BLOCK_PLACE) || set.worldUses(Flags.ALLOW_BLOCK_PLACE)
            ? block.getType() : Material.AIR;
        if (placeRefused(set, player, type)) {
            event.setCancelled(true);
            stopGhostClimb(player, block);
            return;
        }
        // A bed or door also fills a second position, which may lie in another region.
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (final BlockState replaced : multi.getReplacedBlockStates()) {
                final int x = replaced.getX();
                final int y = replaced.getY();
                final int z = replaced.getZ();
                if ((x != block.getX() || y != block.getY() || z != block.getZ())
                    && placeRefused(query.getApplicableRegions(block.getWorld(), x, y, z), player, type)) {
                    event.setCancelled(true);
                    stopGhostClimb(player, block);
                    return;
                }
            }
        }
    }

    /**
     * The placer's client shows a refused block until the correction arrives, so a player who jumps
     * and places under their own feet can climb on air. Pushing them down takes back the height,
     * unless something real still holds them up.
     *
     * <p>A velocity rather than a teleport. Each refused block used to teleport the player, so
     * someone spamming placements was always freshly teleported, and anticheats and
     * teleport-protection plugins on live servers then dropped the hits aimed at them. A velocity is
     * a motion the client applies and anticheats model, and the block correction arriving with it
     * removes what they were standing on.
     *
     * <p>Does nothing when {@code regions.stop-ghost-climb} is off.
     */
    private void stopGhostClimb(final Player player, final Block block) {
        if (!stopGhostClimb || player.isFlying()) {
            return;
        }
        final BoundingBox box = player.getBoundingBox();
        final int bx = block.getX();
        final int y = block.getY();
        final int bz = block.getZ();
        final double gap = box.getMinY() - (y + 1);
        if (gap < -0.001 || gap > 1.5
            || box.getMinX() >= bx + 1 || box.getMaxX() <= bx
            || box.getMinZ() >= bz + 1 || box.getMaxZ() <= bz) {
            return;
        }
        final World world = player.getWorld();
        for (int x = (int) Math.floor(box.getMinX()); x < box.getMaxX(); x++) {
            for (int z = (int) Math.floor(box.getMinZ()); z < box.getMaxZ(); z++) {
                if ((x != bx || z != bz) && world.getType(x, y, z).isSolid()) {
                    return;
                }
            }
        }
        final Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(velocity.getX(), GHOST_CLIMB_DROP, velocity.getZ()));
    }

    /**
     * Whether placing {@code type} here is refused, telling the player when it is.
     */
    private boolean placeRefused(final ApplicableRegionSet set, final Player player, final Material type) {
        final boolean refused = set.flagSetContains(Flags.DENY_BLOCK_PLACE, type, player.getUniqueId())
            || (!set.flagSetContains(Flags.ALLOW_BLOCK_PLACE, type, player.getUniqueId())
            && !set.testBuild(player.getUniqueId(), Flags.BLOCK_PLACE));
        if (!refused || Bypass.has(player)) {
            return false;
        }
        messages.sendRegionDeny(player, Flags.BLOCK_PLACE, set);
        return true;
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
        if (explicit == null && !set.flagSetContains(denied, material, player.getUniqueId())) {
            if (set.flagSetContains(allowed, material, player.getUniqueId())) {
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
        messages.sendRegionDeny(player, explicit == State.DENY ? bucket : flag, set);
    }

    /**
     * The block a filled bucket puts into the world. Mob buckets carry water with them, so they
     * count as placing water.
     */
    static Material fluidOf(final Material bucket) {
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
    static Material fluidIn(final Block block) {
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
        messages.sendRegionDeny(player, Flags.BLOCK_PLACE, set);
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
        messages.sendRegionDeny(player, Flags.BLOCK_PLACE, set);
    }

    /**
     * {@code interact}/{@code use} for right-clicking a block, the way WorldGuard judges it. A click
     * that uses nothing, such as placing a block on the floor or throwing a wind charge at it, is not
     * an interaction, so {@code block-place} alone decides placement. A refused click denies the
     * block's own use and leaves the held item usable, unless that item would change the block.
     *
     * <p>Both hands are judged, because the client tries the off hand once the main hand comes back
     * unused, and glowstone, compost or a bucket held there would still reach the block. Only the
     * main-hand attempt prints a reason.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null) {
            return;
        }
        final Material item = event.getMaterial();
        final Material type = block.getType();
        if (!InteractFlags.usesBlock(type, item) || InteractionWhitelist.allows(block.getWorld(), type)) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (!set.testBuild(player.getUniqueId(), Flags.INTERACT, Flags.USE)) {
            if (Bypass.has(player)) {
                return;
            }
            if (InteractFlags.explicitlyAllowed(set, player.getUniqueId(), block, type)) {
                return;
            }
            if (InteractFlags.changesBlock(item)) {
                event.setCancelled(true);
            } else {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
            if (event.getHand() == EquipmentSlot.HAND) {
                messages.sendRegionDeny(player, Flags.INTERACT, set);
            }
        }
    }

    /**
     * Punching a dragon egg teleports it, so a left click on one answers to {@code interact}/{@code use}
     * like a right click does, as in WorldGuard.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPunchEgg(final PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.DRAGON_EGG || EventGate.disabled(event)
            || InteractionWhitelist.allows(block.getWorld(), Material.DRAGON_EGG)) {
            return;
        }
        final Player player = event.getPlayer();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        if (set.testBuild(player.getUniqueId(), Flags.INTERACT, Flags.USE) || Bypass.has(player)
            || InteractFlags.explicitlyAllowed(set, player.getUniqueId(), block, Material.DRAGON_EGG)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        messages.sendRegionDeny(player, Flags.INTERACT, set);
    }

    /**
     * Pressure plates and tripwire answer to {@code interact}/{@code use} like a button does, so a
     * non-member cannot open a protected door by stepping on its plate. Nothing is sent to the player,
     * since the event repeats every tick they stand there. No narrower flag covers these blocks, so
     * {@link InteractFlags#explicitlyAllowed} is not consulted.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStep(final PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final Material type = block.getType();
        if (!TRIGGERS.contains(type) || EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        if (!mayTrigger(block, type, player.getUniqueId()) && !Bypass.has(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * The same check for an arrow a player shot or an item a player threw onto the plate. Mobs are
     * left alone, which mob farms rely on.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityStep(final EntityInteractEvent event) {
        final Block block = event.getBlock();
        final Material type = block.getType();
        if (!TRIGGERS.contains(type) || EventGate.disabled(event)) {
            return;
        }
        final Entity entity = event.getEntity();
        if (entity instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter
                && !mayTrigger(block, type, shooter.getUniqueId()) && !Bypass.has(shooter)) {
                event.setCancelled(true);
            }
        } else if (entity instanceof Item item) {
            final UUID thrower = item.getThrower();
            if (thrower != null && !mayTrigger(block, type, thrower)) {
                final Player player = Bukkit.getPlayer(thrower);
                if (player == null || !Bypass.has(player)) {
                    event.setCancelled(true);
                }
            }
        } else if (entity instanceof Vehicle && !entity.isEmpty()) {
            for (final Entity passenger : entity.getPassengers()) {
                if (passenger instanceof Player rider && !mayTrigger(block, type, rider.getUniqueId())
                    && !Bypass.has(rider)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private boolean mayTrigger(final Block block, final Material type, final UUID uuid) {
        return InteractionWhitelist.allows(block.getWorld(), type)
            || query.getApplicableRegions(block).testBuild(uuid, Flags.INTERACT, Flags.USE);
    }

    private static Set<Material> triggers() {
        final Set<Material> triggers = EnumSet.of(Material.TRIPWIRE);
        for (final Material material : Material.values()) {
            if (!material.isLegacy() && (material.name().endsWith("_PRESSURE_PLATE")
                || material.name().endsWith("_BUTTON"))) {
                triggers.add(material);
            }
        }
        return triggers;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(final EntityDamageByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof Player defender) || !query.usesFlag(defender.getWorld(), Flags.PVP)) {
            return;
        }
        final Player attacker = Attackers.of(event.getDamager());
        if (attacker == null || attacker.equals(defender)) {
            return;
        }
        if (!query.getApplicableRegions(defender).testState(Flags.PVP, attacker.getUniqueId())
            || !query.getApplicableRegions(attacker).testState(Flags.PVP, attacker.getUniqueId())) {
            if (Bypass.has(attacker)) {
                return;
            }
            if (Attackers.pvpRefusalOverridden(attacker, defender, event)) {
                return;
            }
            event.setCancelled(true);
            if (DenyDebug.watching(attacker)) {
                final ApplicableRegionSet at = query.getApplicableRegions(defender);
                DenyDebug.explain(attacker, Flags.PVP, at.testState(Flags.PVP, attacker.getUniqueId())
                    ? query.getApplicableRegions(attacker) : at);
            }
        }
    }
}
