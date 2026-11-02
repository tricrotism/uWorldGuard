package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chunk cache and the grid it fills from, checked against a scan of every region. Points spread
 * over far more chunks than the cache holds, so entries are displaced and refilled throughout.
 */
class ChunkCacheTest {

    private static Set<ProtectedRegion> at(final RegionManager manager, final int x, final int y, final int z) {
        final ApplicableRegionSet set = manager.getApplicableRegions(x, y, z);
        final Set<ProtectedRegion> found = new HashSet<>();
        for (int i = 0; i < set.size(); i++) {
            found.add(set.get(i));
        }
        return found;
    }

    private static Set<ProtectedRegion> scan(final List<ProtectedRegion> regions, final int x, final int y, final int z) {
        final Set<ProtectedRegion> found = new HashSet<>();
        for (final ProtectedRegion region : regions) {
            if (region.contains(x, y, z)) {
                found.add(region);
            }
        }
        return found;
    }

    @Test
    void lookupsAcrossAWideWorldMatchAScanOfEveryRegion() {
        final RegionManager manager = new RegionManager();
        final Random random = new Random(11);
        final List<ProtectedRegion> regions = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            final int x = random.nextInt(8000) - 4000;
            final int z = random.nextInt(8000) - 4000;
            final int span = i % 20 == 0 ? 3000 : 60;
            final ProtectedRegion region = new ProtectedCuboidRegion("r" + i, BlockVector3.at(x, 0, z),
                BlockVector3.at(x + random.nextInt(span), 255, z + random.nextInt(span)));
            regions.add(region);
            manager.addRegion(region);
        }
        int inside = 0;
        for (int i = 0; i < 20000; i++) {
            final int x = random.nextInt(9000) - 4500;
            final int z = random.nextInt(9000) - 4500;
            final Set<ProtectedRegion> expected = scan(regions, x, 64, z);
            assertEquals(expected, at(manager, x, 64, z), "at " + x + " " + z);
            inside += expected.isEmpty() ? 0 : 1;
        }
        assertTrue(inside > 1000, "enough points land in regions to mean something");
    }

    private static ProtectedRegion box(final String id, final Random random) {
        final int x = random.nextInt(2000) - 1000;
        final int z = random.nextInt(2000) - 1000;
        // one in ten is wide enough for the grid's large list
        final int span = random.nextInt(10) == 0 ? 1500 : 40;
        return new ProtectedCuboidRegion(id, BlockVector3.at(x, 0, z),
            BlockVector3.at(x + random.nextInt(span), 255, z + random.nextInt(span)));
    }

    @Test
    void editsBetweenLookupsMatchAScanOfEveryRegion() {
        final RegionManager manager = new RegionManager();
        final Random random = new Random(5);
        final List<ProtectedRegion> live = new ArrayList<>();
        int next = 0;
        for (int round = 0; round < 300; round++) {
            // now and then a burst longer than the edit log, so lookups cross its wrap too
            final int edits = round % 50 == 0 ? 100 : random.nextInt(4);
            for (int e = 0; e < edits; e++) {
                final int pick = random.nextInt(4);
                if (pick == 0 && !live.isEmpty()) {
                    manager.removeRegion(live.remove(random.nextInt(live.size())).getId());
                } else if (pick == 1 && !live.isEmpty()) {
                    final int i = random.nextInt(live.size());
                    final ProtectedRegion moved = box(live.get(i).getId(), random);
                    manager.redefineRegion(moved);
                    live.set(i, moved);
                } else {
                    final ProtectedRegion added = box("r" + next++, random);
                    manager.addRegion(added);
                    live.add(added);
                }
            }
            for (int i = 0; i < 200; i++) {
                final int x = random.nextInt(2400) - 1200;
                final int z = random.nextInt(2400) - 1200;
                assertEquals(scan(live, x, 64, z), at(manager, x, 64, z), "round " + round + " at " + x + " " + z);
            }
        }
    }

    @Test
    void denseChunksShareTheirSetsAndMatchAScan() {
        final RegionManager manager = new RegionManager();
        final List<ProtectedRegion> regions = new ArrayList<>();
        final ProtectedRegion mall = new ProtectedCuboidRegion("mall", BlockVector3.at(0, 0, 0), BlockVector3.at(63, 255, 63));
        regions.add(mall);
        manager.addRegion(mall);
        // sixteen 4 by 4 shops in each chunk, seventeen candidates with the mall
        for (int x = 0; x < 64; x += 4) {
            for (int z = 0; z < 64; z += 4) {
                final ProtectedRegion shop = new ProtectedCuboidRegion("shop" + x + "_" + z,
                    BlockVector3.at(x, 0, z), BlockVector3.at(x + 3, 255, z + 3));
                regions.add(shop);
                manager.addRegion(shop);
            }
        }
        final Random random = new Random(3);
        for (int i = 0; i < 5000; i++) {
            final int x = random.nextInt(70) - 3;
            final int z = random.nextInt(70) - 3;
            assertEquals(scan(regions, x, 64, z), at(manager, x, 64, z), "at " + x + " " + z);
        }

        assertSame(manager.getApplicableRegions(5, 64, 5), manager.getApplicableRegions(6, 70, 6),
            "two points in one shop share a set");
        manager.getRegion("shop4_4").setFlag(Flags.PVP, State.DENY);
        assertEquals(State.DENY, manager.getApplicableRegions(5, 64, 5).queryState(Flags.PVP));
    }

    @Test
    void chunksPastThirtyTwoCandidatesStillShareSetsAndMatchAScan() {
        final RegionManager manager = new RegionManager();
        final List<ProtectedRegion> regions = new ArrayList<>();
        final ProtectedRegion spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        regions.add(spawn);
        manager.addRegion(spawn);
        // three floors of sixteen 4 by 4 rooms, so 48 rooms plus spawn in one chunk
        for (int floor = 0; floor < 3; floor++) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    final ProtectedRegion room = new ProtectedCuboidRegion("room" + floor + "_" + x + "_" + z,
                        BlockVector3.at(x, floor * 20, z), BlockVector3.at(x + 3, floor * 20 + 19, z + 3));
                    regions.add(room);
                    manager.addRegion(room);
                }
            }
        }
        // not a cuboid, so its box hits still go through contains
        final ProtectedRegion fountain = new ProtectedCylinderRegion("fountain", 8, 8, 3, 3, 0, 255);
        regions.add(fountain);
        manager.addRegion(fountain);
        assertEquals(50, manager.regionsOverlappingChunk(8, 8));

        final Random random = new Random(9);
        for (int i = 0; i < 5000; i++) {
            final int x = random.nextInt(16);
            final int y = random.nextInt(70);
            final int z = random.nextInt(16);
            assertEquals(scan(regions, x, y, z), at(manager, x, y, z), "at " + x + " " + y + " " + z);
        }

        assertSame(manager.getApplicableRegions(1, 45, 1), manager.getApplicableRegions(2, 50, 2),
            "two points in one room share a set");
        manager.getRegion("room2_0_0").setFlag(Flags.PVP, State.DENY);
        assertEquals(State.DENY, manager.getApplicableRegions(1, 45, 1).queryState(Flags.PVP));
    }

    @Test
    void anEditAfterLookupsReachesTheChunksAlreadyCached() {
        final RegionManager manager = new RegionManager();
        manager.addRegion(new ProtectedCuboidRegion("a", BlockVector3.at(-100, 0, -100), BlockVector3.at(-80, 255, -80)));
        assertTrue(at(manager, -50, 64, -50).isEmpty());

        final ProtectedRegion b = new ProtectedCuboidRegion("b", BlockVector3.at(-60, 0, -60), BlockVector3.at(-40, 255, -40));
        manager.addRegion(b);
        assertEquals(Set.of(b), at(manager, -50, 64, -50));

        manager.removeRegion("b");
        assertTrue(at(manager, -50, 64, -50).isEmpty());
    }
}
