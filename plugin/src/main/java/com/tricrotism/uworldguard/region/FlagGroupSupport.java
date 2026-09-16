package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.flags.State;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which flags actually honour a {@code <flag>-group} qualifier today.
 *
 * <p>A group only takes effect where the enforcing listener knows who is acting. Most protection
 * flags do; environmental ones (explosions, block spread, chunk unload) have no player to judge, and
 * a handful more have a player available that is not yet threaded through. On those, a qualifier is
 * stored and round-trips but is evaluated as "non-member", which for a {@code deny} means it applies
 * to everyone — stricter than the author intended.
 *
 * <p>That silent over-restriction is exactly the migration failure this class exists to surface, so
 * the qualifier is never dropped; it is reported instead. Keep {@link #HONOURED} in step with the
 * listeners: a flag belongs here only once its enforcement passes a subject.
 */
@NullMarked
public final class FlagGroupSupport {

    private static final Set<Flag<?>> HONOURED = Set.of(
        // ApplicableRegionSet.canBuild
        Flags.BUILD,
        // BuildProtectionListener.onBreak
        Flags.BLOCK_BREAK,
        // BuildProtectionListener.onPlace
        Flags.BLOCK_PLACE,
        // BuildProtectionListener.onInteract
        Flags.INTERACT,
        Flags.USE,
        // BuildProtectionListener.checkBucket
        Flags.BUCKET_FILL,
        Flags.BUCKET_EMPTY,
        // BuildProtectionListener.onPvp (judged against the attacker)
        Flags.PVP,
        // PlayerStateListener.onChestAccess
        Flags.CHEST_ACCESS,
        // PlayerStateListener.onItemDamage
        Flags.ITEM_DURABILITY,
        // TravelListener.onGlide
        Flags.GLIDE,
        // EntityListener.onEntityDamage (victim is the player)
        Flags.MOB_DAMAGE,
        // MovementListener crossing + containment sweep, and onMount
        Flags.ENTRY,
        // MovementListener crossing
        Flags.EXIT,
        // PlayerStateListener.onSleep
        Flags.SLEEP,
        // PlayerStateListener.onRide
        Flags.RIDE,
        // PlayerStateListener.onDamage
        Flags.FALL_DAMAGE,
        // TravelListener.onPortal (the player-driven path)
        Flags.NETHER_PORTALS,
        // WorkbenchListener.onOpen
        Flags.PERMIT_WORKBENCHES,
        // WorkbenchListener.onCraft
        Flags.INVENTORY_CRAFT,
        // CropTrampleListener.onPlayerTrample
        Flags.CROP_TRAMPLE,
        // NaturalListener.onForm
        Flags.FROSTWALKER,
        // ItemUseListener.onTrade
        Flags.VILLAGER_TRADE,
        // EndCrystalListener.onPlace
        Flags.END_CRYSTAL_PLACE,
        // EndCrystalListener.onAttack
        Flags.END_CRYSTAL_INTERACT,
        // WorldEditFlagGuard.onEditSession
        Flags.WORLDEDIT,
        // InteractionListener.deny / denyAt
        Flags.SHEAR,
        Flags.LEASH,
        Flags.NAME_ENTITY,
        Flags.BUCKET_ENTITY,
        Flags.ARMOR_STAND_MANIPULATE,
        Flags.MANNEQUIN_MANIPULATE,
        Flags.FLOWER_POT,
        Flags.LECTERN,
        Flags.SIGN_EDIT,
        // VehicleListener
        Flags.VEHICLE_PLACE,
        Flags.VEHICLE_DESTROY,
        // EntityListener.onEntityDamage / onHangingBreak
        Flags.DAMAGE_ANIMALS,
        Flags.ENTITY_ITEM_FRAME_DESTROY,
        Flags.ENTITY_PAINTING_DESTROY,
        // MachineListener.onVault
        Flags.VAULT_USE,
        // PlayerStateListener.onChestAccess (the respawn-anchor branch)
        Flags.RESPAWN_ANCHORS
    );

    /**
     * One region's use of a group qualifier that will not behave as written.
     */
    public record Finding(String world, String region, String flag, RegionGroup group) {}

    private FlagGroupSupport() {}

    public static boolean honoursGroup(final Flag<?> flag) {
        return HONOURED.contains(flag);
    }

    /**
     * Every group qualifier in {@code manager} that its flag does not yet honour.
     */
    public static List<Finding> audit(final String world, final RegionManager manager) {
        final List<Finding> findings = new ArrayList<>();
        for (final ProtectedRegion region : manager.getRegions()) {
            for (final Map.Entry<Flag<?>, RegionGroup> entry : region.getFlagGroups().entrySet()) {
                if (entry.getValue() != RegionGroup.ALL && !honoursGroup(entry.getKey())) {
                    findings.add(new Finding(
                        world, region.getId(), entry.getKey().getName(), entry.getValue()));
                }
            }
        }
        return findings;
    }

    /**
     * Regions that trust a permission group as an owner or member. Nothing resolves those to players
     * — {@link ProtectedRegion#isMember} consults the uuid sets only — so the trust they express is
     * not applied: those players are treated as visitors and refused like anyone else.
     *
     * <p>Reported at load because the data survives a round trip through storage untouched. A
     * WorldGuard region trusting {@code g:staff} migrates cleanly, keeps its groups in the file, and
     * silently grants nobody anything; without this there is nothing to notice.
     */
    public static List<String> groupTrustRegions(final RegionManager manager) {
        final List<String> ids = new ArrayList<>();
        for (final ProtectedRegion region : manager.getRegions()) {
            if (!region.getOwners().getGroups().isEmpty() || !region.getMembers().getGroups().isEmpty()) {
                ids.add(region.getId());
            }
        }
        return ids;
    }

    /**
     * Regions where {@code passthrough} is allowed — they take no part in build protection. Reported
     * at startup because the flag is newly honoured: a region carrying it now stops protecting, which
     * is correct WorldGuard behaviour but a change worth stating out loud rather than discovering.
     */
    public static List<String> passthroughRegions(final RegionManager manager) {
        final List<String> ids = new ArrayList<>();
        for (final ProtectedRegion region : manager.getRegions()) {
            if (region.getFlags().get(Flags.PASSTHROUGH) == State.ALLOW) {
                ids.add(region.getId());
            }
        }
        return ids;
    }
}
