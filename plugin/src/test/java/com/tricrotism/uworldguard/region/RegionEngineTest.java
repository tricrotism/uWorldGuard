package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void pointsInTheSameRegionsShareOneSet() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("claim", 0, 0, 15, 15));

        assertSame(manager.getApplicableRegions(2, 64, 2), manager.getApplicableRegions(12, 70, 9));
    }

    @Test
    void aSetHeldAcrossAFlagEditAnswersWithTheNewValue() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion claim = cuboid("claim", 0, 0, 15, 15);
        claim.setFlag(Flags.TNT, State.DENY);
        claim.setFlag(Flags.GREETING, "hello");
        manager.addRegion(claim);
        final ApplicableRegionSet held = manager.getApplicableRegions(8, 64, 8);
        assertEquals(State.ALLOW, held.queryState(Flags.PVP), "memoised as unset");
        assertEquals("hello", held.queryValue(Flags.GREETING));
        assertFalse(held.worldUses(Flags.PVP));

        claim.setFlag(Flags.PVP, State.DENY);
        claim.setFlag(Flags.GREETING, "welcome");

        assertTrue(held.worldUses(Flags.PVP));
        assertEquals(State.DENY, held.queryState(Flags.PVP));
        assertEquals("welcome", held.queryValue(Flags.GREETING));
        assertEquals(State.DENY, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP));
    }

    @Test
    void copyingStateOntoAHeldRegionRetiresSetsHoldingItsOldValues() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion claim = cuboid("claim", 0, 0, 15, 15);
        claim.setFlag(Flags.PVP, State.ALLOW);
        manager.addRegion(claim);
        assertEquals(State.ALLOW, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP));
        manager.clearDirty();
        final ProtectedCuboidRegion template = cuboid("template", 100, 100, 115, 115);
        template.setFlag(Flags.PVP, State.DENY);

        claim.copyStateFrom(template);

        assertEquals(State.DENY, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP),
            "an overwritten value reaches the shared set");
        assertTrue(manager.clearDirty(), "the world is marked for saving");
    }

    @Test
    void aPriorityChangeAfterALookupReordersTheSharedSet() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion outer = cuboid("outer", 0, 0, 15, 15);
        outer.setFlag(Flags.PVP, State.ALLOW);
        final ProtectedCuboidRegion inner = cuboid("inner", 0, 0, 15, 15);
        inner.setFlag(Flags.PVP, State.DENY);
        inner.setPriority(10);
        manager.addRegion(outer);
        manager.addRegion(inner);
        assertEquals(State.DENY, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP));

        outer.setPriority(20);

        assertEquals(State.ALLOW, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP),
            "a set still sorted the old way meets the deny first and keeps it");
    }

    @Test
    void aGroupQualifierAddedAfterALookupIsHonoured() {
        final RegionManager manager = new RegionManager();
        final UUID member = UUID.randomUUID();
        final ProtectedCuboidRegion town = cuboid("town", 0, 0, 15, 15);
        town.getMembers().addPlayer(member);
        town.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(town);
        assertEquals(State.DENY, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP, member));

        town.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);

        assertEquals(State.ALLOW, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP, member));
    }

    @Test
    void aSetHeldAcrossAGroupQualifierEditHonoursIt() {
        final RegionManager manager = new RegionManager();
        final UUID member = UUID.randomUUID();
        final ProtectedCuboidRegion town = cuboid("town", 0, 0, 15, 15);
        town.getMembers().addPlayer(member);
        town.setFlag(Flags.PVP, State.DENY);
        town.setFlag(Flags.GREETING, "keep out");
        manager.addRegion(town);
        final ApplicableRegionSet held = manager.getApplicableRegions(8, 64, 8);
        assertEquals(State.DENY, held.queryState(Flags.PVP, member));

        town.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        town.setFlagGroup(Flags.GREETING, RegionGroup.NON_MEMBERS);

        assertEquals(State.ALLOW, held.queryState(Flags.PVP, member));
        assertNull(held.queryValue(Flags.GREETING, member));
    }

    @Test
    void aDenyListQualifiedToNonMembersSparesMembers() {
        final RegionManager manager = new RegionManager();
        final UUID member = UUID.randomUUID();
        final UUID stranger = UUID.randomUUID();
        final ProtectedCuboidRegion town = cuboid("town", 0, 0, 15, 15);
        town.getMembers().addPlayer(member);
        town.setFlag(Flags.BLOCKED_CMDS, java.util.Set.of("/home"));
        town.setFlagGroup(Flags.BLOCKED_CMDS, RegionGroup.NON_MEMBERS);
        manager.addRegion(town);

        final ApplicableRegionSet set = manager.getApplicableRegions(8, 64, 8);

        assertTrue(set.flagSetContains(Flags.BLOCKED_CMDS, "/home", stranger));
        assertFalse(set.flagSetContains(Flags.BLOCKED_CMDS, "/home", member));
        assertTrue(set.flagSetContains(Flags.BLOCKED_CMDS, "/home"), "the subjectless check ignores groups");
    }

    @Test
    void aGlobalBuildDenyQualifiedToOwnersSparesEveryoneElse() {
        final RegionManager manager = new RegionManager();
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.BUILD, State.DENY);
        global.setFlagGroup(Flags.BUILD, RegionGroup.OWNERS);
        manager.addRegion(global);

        assertTrue(manager.getApplicableRegions(500, 64, 500).canBuild(UUID.randomUUID()),
            "a deny restricted to owners leaves a non-owner unaffected");
    }

    @Test
    void aRegionAddedAfterALookupJoinsTheSet() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("claim", 0, 0, 15, 15));
        assertEquals(1, manager.getApplicableRegions(8, 64, 8).size());

        manager.addRegion(cuboid("event", 4, 4, 10, 10));

        assertEquals(2, manager.getApplicableRegions(8, 64, 8).size());
        assertEquals(1, manager.getApplicableRegions(2, 64, 2).size());
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
    void aPassthroughRegionAboveAClaimLeavesTheClaimToDecide() {
        final UUID member = UUID.randomUUID();
        final UUID stranger = UUID.randomUUID();
        final ProtectedCuboidRegion cover = cuboid("cover", 0, 0, 64, 64);
        cover.setFlag(Flags.PASSTHROUGH, State.ALLOW);
        cover.setPriority(10);
        final ProtectedCuboidRegion claim = cuboid("claim", 0, 0, 32, 32);
        claim.getMembers().addPlayer(member);

        final ApplicableRegionSet covered = new ApplicableRegionSet(List.of(cover, claim), null);
        assertFalse(covered.canBuild(stranger));
        assertTrue(covered.canBuild(member), "the claim below the passthrough region is the top");

        final ProtectedCuboidRegion sibling = cuboid("sibling", 0, 0, 32, 32);
        sibling.setFlag(Flags.PASSTHROUGH, State.ALLOW);
        sibling.setFlag(Flags.BUILD, State.ALLOW);
        final ApplicableRegionSet withSibling = new ApplicableRegionSet(List.of(cover, claim, sibling), null);
        assertFalse(withSibling.canBuild(stranger), "a passthrough sibling's build flag takes no part");
        assertTrue(withSibling.canBuild(member));
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
    void removingOrReplacingAParentStillReachesItsChildrenAfterAReparent() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion first = cuboid("first", 0, 0, 64, 64);
        final ProtectedCuboidRegion second = cuboid("second", 0, 0, 64, 64);
        final ProtectedCuboidRegion child = cuboid("child", 8, 8, 16, 16);
        manager.addRegion(first);
        manager.addRegion(second);
        manager.addRegion(child);
        child.setParent(first);
        child.setParent(second);

        manager.removeRegion("first");
        assertSame(second, child.getParent(), "first lost its only child to the reparent");

        final ProtectedCuboidRegion replacement = cuboid("second", 0, 0, 128, 128);
        manager.addRegion(replacement);
        assertSame(replacement, child.getParent());

        manager.removeRegion("second");
        assertNull(child.getParent());
    }

    @Test
    void editsThroughAReplacedInstanceNoLongerReachTheWorld() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion added = cuboid("plot", 0, 0, 32, 32);
        manager.addRegion(added);
        final ProtectedCuboidRegion redefined = cuboid("plot", 0, 0, 64, 64);
        manager.addRegion(redefined);
        manager.redefineRegion(cuboid("plot", 0, 0, 128, 128));
        manager.clearDirty();

        added.setFlag(Flags.PVP, State.DENY);
        redefined.setFlag(Flags.PVP, State.DENY);

        assertFalse(manager.clearDirty(), "a dead instance must not mark the world dirty");
        assertFalse(manager.anyRegionUses(Flags.PVP));
    }

    @Test
    void replacingAChildReleasesItsParent() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion parent = cuboid("mall", 0, 0, 64, 64);
        final ProtectedCuboidRegion child = cuboid("shop", 8, 8, 24, 24);
        child.setParent(parent);
        manager.addRegion(parent);
        manager.addRegion(child);

        manager.redefineRegion(cuboid("shop", 8, 8, 32, 32));
        assertTrue(parent.uwgHasChildren(), "the redefined child still names it");

        manager.addRegion(cuboid("shop", 8, 8, 32, 32));
        assertFalse(parent.uwgHasChildren(), "no region names it any more");
    }

    @Test
    void mixedCaseIdsFollowReplacementAndRemoval() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion first = cuboid("Spawn", 0, 0, 32, 32);
        manager.addRegion(first);
        assertSame(first, manager.getRegion("Spawn"));

        final ProtectedCuboidRegion second = cuboid("SPAWN", 0, 0, 32, 32);
        manager.addRegion(second);
        assertSame(second, manager.getRegion("Spawn"));
        assertSame(second, manager.getRegion("SPAWN"));

        manager.removeRegion("spawn");
        assertNull(manager.getRegion("Spawn"));
        assertNull(manager.getRegion("SPAWN"));
    }

    @Test
    void aFlagSetAfterTheIndexWasBuiltIsSeen() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion plot = cuboid("plot", 0, 0, 32, 32);
        manager.addRegion(plot);
        assertFalse(manager.anyRegionUses(Flags.PVP));

        plot.setFlag(Flags.PVP, State.DENY);

        assertTrue(manager.anyRegionUses(Flags.PVP));
        assertEquals(State.DENY, manager.getApplicableRegions(16, 64, 16).queryState(Flags.PVP));
    }

    @Test
    void groupQualifiedValuesAreSkippedForSubjectsOutsideTheGroup() {
        final RegionManager manager = new RegionManager();
        final ProtectedCuboidRegion low = cuboid("low", 0, 0, 32, 32);
        low.setFlag(Flags.GREETING, "everyone");
        final ProtectedCuboidRegion high = cuboid("high", 0, 0, 32, 32);
        high.setPriority(10);
        high.setFlag(Flags.GREETING, "owners only");
        high.setFlagGroup(Flags.GREETING, RegionGroup.OWNERS);
        manager.addRegion(low);
        manager.addRegion(high);

        final ApplicableRegionSet set = manager.getApplicableRegions(16, 64, 16);

        assertEquals("everyone", set.queryValue(Flags.GREETING, null));
        assertEquals("owners only", set.queryValue(Flags.GREETING), "the subject-less form is unchanged");
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

    @Test
    void smallBoxesReadTheChunkCacheWithTheScansAnswer() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(cuboid("wide", -40, -40, 40, 40));
        manager.addRegion(cuboid("east", 10, 0, 20, 8));
        manager.addRegion(new ProtectedCuboidRegion("sky", BlockVector3.at(0, 200, 0), BlockVector3.at(16, 255, 8)));
        manager.getRegion("east").setPriority(5);

        final List<ProtectedRegion> straddling = manager.getRegionsIntersecting(
            BlockVector3.at(12, 64, -4), BlockVector3.at(20, 66, 4));
        assertEquals(List.of(manager.getRegion("east"), manager.getRegion("wide")), straddling,
            "a region over all four chunks is reported once, by priority, and the Y bound excludes sky");

        final List<ProtectedRegion> scanned = manager.getRegionsIntersecting(
            BlockVector3.at(-30, 64, -4), BlockVector3.at(30, 66, 4));
        assertEquals(List.of(manager.getRegion("east"), manager.getRegion("wide")), scanned,
            "a box wider than four chunks scans and agrees");
    }

    @Test
    void aParentSetStraightOnTheRegionReachesSetsAlreadyShared() {
        final RegionManager manager = new RegionManager();
        final ProtectedRegion allow = cuboid("allow", 1000, 1000, 1010, 1010);
        allow.setFlag(Flags.PVP, State.ALLOW);
        final ProtectedRegion deny = cuboid("deny", 2000, 2000, 2010, 2010);
        deny.setFlag(Flags.PVP, State.DENY);
        final ProtectedRegion child = cuboid("child", 0, 0, 15, 15);
        child.setParent(allow);
        manager.addRegion(allow);
        manager.addRegion(deny);
        manager.addRegion(child);
        assertEquals(State.ALLOW, manager.getApplicableRegions(5, 64, 5).queryState(Flags.PVP));

        child.setParent(deny);

        assertEquals(State.DENY, manager.getApplicableRegions(5, 64, 5).queryState(Flags.PVP));
    }
}
