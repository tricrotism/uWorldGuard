package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of the query engine every protection check runs through: which regions apply at a point,
 * how a flag resolves across priorities and parents, and that the per-chunk candidate cache answers
 * with the regions that exist now rather than the ones that existed when it was filled.
 */
class RegionEngineTest {

    private static ProtectedCuboidRegion cuboid(final String id, final int x1, final int z1, final int x2, final int z2) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(x1, 0, z1), BlockVector3.at(x2, 255, z2));
    }

    @Test
    void wildernessAnswersWithTheSharedEmptySet() {
        final RegionManager manager = new RegionManager();

        final ApplicableRegionSet first = manager.getApplicableRegions(0, 64, 0);
        final ApplicableRegionSet second = manager.getApplicableRegions(4000, 64, -4000);

        assertTrue(first.isEmpty());
        assertSame(first, second, "the empty result is cached, not rebuilt per query");
    }

    @Test
    void appliesRegionsContainingThePoint() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("spawn", 0, 0, 32, 32));

        assertEquals(1, manager.getApplicableRegions(16, 64, 16).size());
        assertTrue(manager.getApplicableRegions(33, 64, 16).isEmpty());
        assertTrue(manager.getApplicableRegions(16, 64, 33).isEmpty());
    }

    @Test
    void yBoundsAreTestedEvenWhenTheChunkMatches() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(new ProtectedCuboidRegion("cellar",
            BlockVector3.at(0, 0, 0), BlockVector3.at(16, 32, 16)));

        assertEquals(1, manager.getApplicableRegions(8, 16, 8).size());
        assertTrue(manager.getApplicableRegions(8, 64, 8).isEmpty(),
            "same chunk, above the region's maximum Y");
    }

    @Test
    void highestPriorityComesFirst() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion outer = cuboid("outer", 0, 0, 64, 64);
        final ProtectedCuboidRegion inner = cuboid("inner", 8, 8, 24, 24);
        inner.setPriority(10);
        manager.addRegion(outer);
        manager.addRegion(inner);

        final ApplicableRegionSet set = manager.getApplicableRegions(16, 64, 16);

        assertEquals(2, set.size());
        assertEquals("inner", set.get(0).getId());
        assertEquals("outer", set.get(1).getId());
    }

    @Test
    void higherPriorityWinsAndTiesFavourDeny() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion low = cuboid("low", 0, 0, 64, 64);
        low.setFlag(Flags.PVP, State.DENY);
        final ProtectedCuboidRegion high = cuboid("high", 0, 0, 64, 64);
        high.setPriority(5);
        high.setFlag(Flags.PVP, State.ALLOW);
        manager.addRegion(low);
        manager.addRegion(high);

        assertEquals(State.ALLOW, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));

        final ProtectedCuboidRegion tie = cuboid("tie", 0, 0, 64, 64);
        tie.setPriority(5);
        tie.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(tie);

        assertEquals(State.DENY, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP),
            "at equal priority a deny beats an allow");
    }

    @Test
    void flagsInheritFromTheParentChain() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion parent = cuboid("mall", 0, 0, 64, 64);
        parent.setFlag(Flags.PVP, State.DENY);
        final ProtectedCuboidRegion child = cuboid("shop", 8, 8, 24, 24);
        child.setPriority(10);
        child.setParent(parent);
        manager.addRegion(parent);
        manager.addRegion(child);

        assertEquals(State.DENY, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));
        assertEquals(State.DENY, child.getFlag(Flags.PVP));
    }

    @Test
    void theGlobalRegionIsTheLowestPriorityFallbackAndNeverApplicableItself() {
        final RegionManager manager = new RegionManager();
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(global);

        final ApplicableRegionSet wilderness = manager.getApplicableRegions(500, 64, 500);

        assertTrue(wilderness.isEmpty(), "the global region is not spatial");
        assertEquals(State.DENY, wilderness.queryState(Flags.PVP));

        final ProtectedCuboidRegion arena = cuboid("arena", 0, 0, 32, 32);
        arena.setFlag(Flags.PVP, State.ALLOW);
        manager.addRegion(arena);

        assertEquals(State.ALLOW, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));
    }

    @Test
    void groupQualifiersSkipValuesThatDoNotApplyToTheSubject() {
        final RegionManager manager = new RegionManager();
        final UUID member = UUID.randomUUID();
        final UUID stranger = UUID.randomUUID();
        final ProtectedCuboidRegion town = cuboid("town", 0, 0, 64, 64);
        town.getMembers().addPlayer(member);
        town.setFlag(Flags.PVP, State.DENY);
        town.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        manager.addRegion(town);

        final ApplicableRegionSet set = manager.getApplicableRegions(16, 64, 16);

        assertEquals(State.DENY, set.queryState(Flags.PVP, stranger));
        assertEquals(State.ALLOW, set.queryState(Flags.PVP, member),
            "the value is qualified to non-members, so a member falls back to the flag default");
    }

    @Test
    void anyRegionUsesTracksFlagsAcrossEdits() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion region = cuboid("plot", 0, 0, 32, 32);
        manager.addRegion(region);

        assertFalse(manager.anyRegionUses(Flags.PVP));

        region.setFlag(Flags.PVP, State.DENY);

        assertTrue(manager.anyRegionUses(Flags.PVP), "setting a flag retires the index by itself");
        assertFalse(manager.anyRegionUses(Flags.GLIDE));

        region.setFlag(Flags.PVP, null);

        assertFalse(manager.anyRegionUses(Flags.PVP));
    }

    /**
     * Flag resolution short-circuits on the world's flag index, so a flag set on a region already in
     * the manager has to reach every query immediately — this is the failure that index would cause.
     */
    @Test
    void aFlagSetAfterTheRegionIsAddedResolvesAtOnce() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion arena = cuboid("arena", 0, 0, 32, 32);
        manager.addRegion(arena);
        assertEquals(State.ALLOW, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));

        arena.setFlag(Flags.PVP, State.DENY);

        assertEquals(State.DENY, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));
        assertEquals("go away", setGreeting(manager, arena));
    }

    private static String setGreeting(final RegionManager manager, final ProtectedCuboidRegion region) {
        region.setFlag(Flags.GREETING, "go away");
        return manager.getApplicableRegions(16, 64, 16).queryValue(Flags.GREETING);
    }

    @Test
    void aFlagSetOnTheGlobalRegionAfterLoadResolvesAtOnce() {
        final RegionManager manager = new RegionManager();
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        manager.addRegion(global);
        assertTrue(manager.getApplicableRegions(500, 64, 500).isEmpty());

        global.setFlag(Flags.PVP, State.DENY);

        assertEquals(State.DENY, manager.getApplicableRegions(500, 64, 500).queryState(Flags.PVP));
    }

    @Test
    void aDenyListSetAfterTheRegionIsAddedIsHonoured() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion plot = cuboid("plot", 0, 0, 32, 32);
        manager.addRegion(plot);
        assertFalse(manager.getApplicableRegions(16, 64, 16)
            .flagSetContains(Flags.BLOCKED_CMDS, "/home"));

        plot.setFlag(Flags.BLOCKED_CMDS, java.util.Set.of("/home"));

        assertTrue(manager.getApplicableRegions(16, 64, 16)
            .flagSetContains(Flags.BLOCKED_CMDS, "/home"));
    }

    @Test
    void membershipDecidesBuildingWhenNoFlagIsSet() {
        final RegionManager manager = new RegionManager();
        final UUID owner = UUID.randomUUID();
        final UUID stranger = UUID.randomUUID();
        final ProtectedCuboidRegion plot = cuboid("plot", 0, 0, 32, 32);
        plot.getOwners().addPlayer(owner);
        manager.addRegion(plot);

        final ApplicableRegionSet set = manager.getApplicableRegions(16, 64, 16);

        assertTrue(set.canBuild(owner));
        assertFalse(set.canBuild(stranger));
        assertTrue(manager.getApplicableRegions(500, 64, 500).canBuild(stranger), "wilderness is open");
    }

    @Test
    void anExplicitAllowBeatsMembership() {
        final RegionManager manager = new RegionManager();
        final UUID stranger = UUID.randomUUID();
        final ProtectedCuboidRegion spawn = cuboid("spawn", 0, 0, 32, 32);
        spawn.getOwners().addPlayer(UUID.randomUUID());
        spawn.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
        manager.addRegion(spawn);

        final ApplicableRegionSet set = manager.getApplicableRegions(16, 64, 16);

        assertFalse(set.canBuild(stranger));
        assertTrue(set.testBuild(stranger, Flags.BLOCK_PLACE),
            "an explicit allow permits regardless of membership");
        assertFalse(set.testBuild(stranger, Flags.BLOCK_BREAK),
            "a flag nobody set leaves membership to decide");
    }

    @Test
    void passthroughRegionsTakeNoPartInBuildProtection() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion greeting = cuboid("greeting", 0, 0, 64, 64);
        greeting.setFlag(Flags.PASSTHROUGH, State.ALLOW);
        manager.addRegion(greeting);

        assertTrue(manager.getApplicableRegions(16, 64, 16).canBuild(UUID.randomUUID()));
    }

    @Test
    void aRegionAddedAfterAQueryInThatChunkIsSeen() {
        final RegionManager manager = new RegionManager();
        assertTrue(manager.getApplicableRegions(16, 64, 16).isEmpty());

        manager.addRegion(cuboid("late", 0, 0, 32, 32));

        assertEquals(1, manager.getApplicableRegions(16, 64, 16).size(),
            "the chunk candidate cache must be invalidated by an add");
    }

    @Test
    void aRemovedRegionStopsApplyingImmediately() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("gone", 0, 0, 32, 32));
        assertEquals(1, manager.getApplicableRegions(16, 64, 16).size());

        assertNotNull(manager.removeRegion("gone"));

        assertTrue(manager.getApplicableRegions(16, 64, 16).isEmpty());
    }

    @Test
    void redefineMovesTheBoundsAndKeepsTheConfiguration() {
        final RegionManager manager = new RegionManager();
        final UUID owner = UUID.randomUUID();
        final ProtectedCuboidRegion before = cuboid("plot", 0, 0, 32, 32);
        before.setFlag(Flags.PVP, State.DENY);
        before.getOwners().addPlayer(owner);
        before.setPriority(7);
        manager.addRegion(before);
        assertEquals(1, manager.getApplicableRegions(16, 64, 16).size());

        assertSame(before, manager.redefineRegion(cuboid("plot", 100, 100, 132, 132)));

        assertTrue(manager.getApplicableRegions(16, 64, 16).isEmpty(), "the old area is released");
        final ApplicableRegionSet moved = manager.getApplicableRegions(116, 64, 116);
        assertEquals(1, moved.size());
        assertEquals(State.DENY, moved.queryState(Flags.PVP));
        assertEquals(7, moved.get(0).getPriority());
        assertTrue(moved.get(0).isOwner(owner));
    }

    @Test
    void redefineRepointsChildrenAtTheNewInstance() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion parent = cuboid("mall", 0, 0, 64, 64);
        parent.setFlag(Flags.PVP, State.DENY);
        final ProtectedCuboidRegion child = cuboid("shop", 8, 8, 24, 24);
        child.setParent(parent);
        manager.addRegion(parent);
        manager.addRegion(child);

        final ProtectedCuboidRegion reshaped = cuboid("mall", 0, 0, 128, 128);
        manager.redefineRegion(reshaped);

        assertSame(reshaped, child.getParent());
        assertEquals(State.DENY, child.getFlag(Flags.PVP));
    }

    @Test
    void addRegionIfAbsentRefusesATakenId() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion first = cuboid("plot", 0, 0, 32, 32);
        manager.addRegion(first);

        assertSame(first, manager.addRegionIfAbsent(cuboid("plot", 100, 100, 132, 132)));
        assertNull(manager.addRegionIfAbsent(cuboid("other", 100, 100, 132, 132)));
    }

    @Test
    void idsAreCaseInsensitive() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("Spawn", 0, 0, 32, 32));

        assertNotNull(manager.getRegion("spawn"));
        assertNotNull(manager.getRegion("SPAWN"));
        assertTrue(manager.hasRegion("sPaWn"));
    }

    @Test
    void regionsSpanningManyChunksApplyInEachOfThem() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("big", -512, -512, 512, 512));

        for (int x = -512; x <= 512; x += 128) {
            for (int z = -512; z <= 512; z += 128) {
                assertEquals(1, manager.getApplicableRegions(x, 64, z).size(), "at " + x + "," + z);
            }
        }
        assertTrue(manager.getApplicableRegions(513, 64, 0).isEmpty());
    }

    @Test
    void circularParentsAreRefused() {
        final ProtectedCuboidRegion a = cuboid("a", 0, 0, 16, 16);
        final ProtectedCuboidRegion b = cuboid("b", 0, 0, 16, 16);
        b.setParent(a);

        try {
            a.setParent(b);
            throw new AssertionError("expected a circular parent to be refused");
        } catch (final IllegalArgumentException expected) {
            assertSame(a, b.getParent());
        }
    }

    @Test
    void intersectingRegionsAreFoundByBoundingBox() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("a", 0, 0, 32, 32));
        manager.addRegion(cuboid("b", 100, 100, 132, 132));
        manager.addRegion(new GlobalProtectedRegion());

        assertEquals(1, manager.getRegionsIntersecting(
            BlockVector3.at(16, 64, 16), BlockVector3.at(48, 64, 48)).size());
        assertTrue(manager.getRegionsIntersecting(
                BlockVector3.at(60, 64, 60), BlockVector3.at(90, 64, 90)).isEmpty(),
            "the global region is never an intersection hit");
    }
}
