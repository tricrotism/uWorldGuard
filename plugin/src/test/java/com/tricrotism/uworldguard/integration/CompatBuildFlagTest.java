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
 * What a plugin gets back through the WorldGuard API for the {@code build} flag itself and for a
 * region's own flags, where WorldGuard's answers differ from a plain default.
 */
class CompatBuildFlagTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
    void anUnsetBuildFlagIsMembershipNotAllow() {
        final ProtectedCuboidRegion spawn =
            new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        spawn.getMembers().addPlayer(MEMBER);
        manager.addRegion(spawn);

        assertNull(at(3, 3).queryState(player(STRANGER), Flags.BUILD), "a stranger in someone's region");
        assertFalse(at(3, 3).testState(player(STRANGER), Flags.BUILD));
        assertFalse(RegionQuery.uwgTestBuild(at(3, 3), player(STRANGER), Flags.BUILD));
        assertEquals(StateFlag.State.ALLOW, at(3, 3).queryState(player(MEMBER), Flags.BUILD));
        assertEquals(StateFlag.State.ALLOW, at(100, 100).queryState(player(STRANGER), Flags.BUILD),
            "outside every region");
    }

    @Test
    void anUnqualifiedEntryDenyAppliesToNonMembersOnly() {
        final ProtectedCuboidRegion vault =
            new ProtectedCuboidRegion("vault", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        vault.getMembers().addPlayer(MEMBER);
        vault.setFlag(com.tricrotism.uworldguard.flags.Flags.ENTRY, com.tricrotism.uworldguard.flags.State.DENY);
        manager.addRegion(vault);

        assertEquals(StateFlag.State.DENY, at(3, 3).queryState(player(STRANGER), Flags.ENTRY));
        assertEquals(StateFlag.State.ALLOW, at(3, 3).queryState(player(MEMBER), Flags.ENTRY),
            "WorldGuard's entry flag defaults to the non-members group");

        vault.setFlagGroup(com.tricrotism.uworldguard.flags.Flags.ENTRY,
            com.tricrotism.uworldguard.flags.RegionGroup.MEMBERS);

        assertEquals(StateFlag.State.DENY, at(3, 3).queryState(player(MEMBER), Flags.ENTRY),
            "an explicit group still decides");
        assertEquals(StateFlag.State.ALLOW, at(3, 3).queryState(player(STRANGER), Flags.ENTRY));
    }
}
