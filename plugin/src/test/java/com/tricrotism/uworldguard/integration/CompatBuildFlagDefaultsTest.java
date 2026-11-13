package com.tricrotism.uworldguard.integration;

import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.util.BlockVector3;
import com.tricrotism.uworldguard.wgcompat.UuidSubject;
import com.tricrotism.uworldguard.wgcompat.WrappedRegionSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flags WorldGuard pairs with build are unset by default, so {@code testBuild} falls back to
 * membership. ProtectionLib, which Nexo uses, asks {@code testBuild(location, player, BLOCK_BREAK)}
 * and fires its own break event when told yes, which uWorldGuard's listener then refused.
 */
class CompatBuildFlagDefaultsTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<StateFlag> PAIRED = List.of(
        Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.INTERACT, Flags.USE, Flags.CHEST_ACCESS,
        Flags.DAMAGE_ANIMALS, Flags.RIDE, Flags.PVP, Flags.SLEEP, Flags.RESPAWN_ANCHORS, Flags.TNT,
        Flags.PLACE_VEHICLE, Flags.DESTROY_VEHICLE, Flags.LIGHTER, Flags.TRAMPLE_BLOCKS,
        Flags.FROSTED_ICE_FORM, Flags.ITEM_FRAME_ROTATE, Flags.FIREWORK_DAMAGE, Flags.USE_ANVIL,
        Flags.USE_DRIPLEAF, Flags.POTION_SPLASH, Flags.WIND_CHARGE_BURST);

    private final RegionManager manager = new RegionManager();

    private static RegionAssociable player(final UUID uuid) {
        record Subject(UUID uwgUuid) implements RegionAssociable, UuidSubject {
            @Override
            public Association getAssociation(final List<ProtectedRegion> regions) {
                throw new AssertionError("a UUID subject is answered by the engine");
            }
        }
        return new Subject(uuid);
    }

    private WrappedRegionSet at(final int x, final int z) {
        return WrappedRegionSet.of(manager.getApplicableRegions(x, 64, z), manager);
    }

    @Test
    void thePairedFlagsAreUnsetByDefaultAsInWorldGuard() {
        for (final StateFlag flag : PAIRED) {
            assertNull(flag.getDefault(), flag.getName());
        }
        assertEquals(StateFlag.State.ALLOW, Flags.BUILD.getDefault());
    }

    @Test
    void membershipDecidesTestBuildWhereThePairedFlagIsUnset() {
        final ProtectedCuboidRegion spawn =
            new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        spawn.getMembers().addPlayer(MEMBER);
        manager.addRegion(spawn);
        final RegionAssociable foreignStranger = regions -> Association.NON_MEMBER;

        for (final StateFlag flag : List.of(Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.INTERACT, Flags.USE)) {
            assertFalse(RegionQuery.uwgTestBuild(at(3, 3), player(STRANGER), flag), flag.getName());
            assertFalse(RegionQuery.uwgTestBuild(at(3, 3), foreignStranger, flag), flag.getName());
            assertNull(at(3, 3).queryState(player(STRANGER), flag), flag.getName());
            assertTrue(RegionQuery.uwgTestBuild(at(3, 3), player(MEMBER), flag), flag.getName());
            assertTrue(RegionQuery.uwgTestBuild(at(100, 100), player(STRANGER), flag), flag.getName());
        }
    }
}
