package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.openjdk.jmh.annotations.*;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Nanoseconds per flag query made on behalf of a player, against a set already in hand.
 *
 * <p>{@code kind}: {@code wilderness} is the shared empty set, {@code shared} a cached set in a claim,
 * {@code held} that set kept past an unrelated flag edit, {@code dense} a one-lookup set from a chunk
 * with too many regions to cache. {@code groups}: {@code none} has no qualifier in the world,
 * {@code elsewhere} has one on a distant region, so every check pays for groups without any changing
 * the answer.
 */
@org.openjdk.jmh.annotations.State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SubjectQueryBenchmark {

    @Param({"wilderness", "shared", "held", "dense"})
    public String kind;

    @Param({"none", "elsewhere"})
    public String groups;

    private ApplicableRegionSet set;
    private UUID stranger;

    @Setup
    public void setUp() {
        final RegionManager manager = new RegionManager();
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.PVP, State.DENY);
        global.setFlag(Flags.GREETING, "wild");
        global.setFlag(Flags.BLOCKED_CMDS, Set.of("/sethome"));
        manager.addRegion(global);

        final ProtectedCuboidRegion far = cuboid("far", 9000, 0, 9000, 9010, 255, 9010);
        far.setFlag(Flags.USE, State.DENY);
        far.setFlag(Flags.BUILD, State.DENY);
        manager.addRegion(far);
        if ("elsewhere".equals(groups)) {
            far.setFlagGroup(Flags.USE, RegionGroup.NON_MEMBERS);
        }

        final ProtectedCuboidRegion parent = cuboid("town", -64, 0, -64, 64, 255, 64);
        parent.setFlag(Flags.PVP, State.ALLOW);
        manager.addRegion(parent);
        final ProtectedCuboidRegion claim = cuboid("claim", 0, 0, 0, 15, 255, 15);
        claim.setPriority(10);
        claim.setParent(parent);
        claim.getMembers().addPlayer(UUID.randomUUID());
        claim.setFlag(Flags.GREETING, "hello");
        claim.setFlag(Flags.BLOCKED_CMDS, Set.of("/home", "/spawn"));
        manager.addRegion(claim);

        if ("dense".equals(kind)) {
            for (int i = 0; i < 80; i++) {
                final ProtectedCuboidRegion room = cuboid("room" + i, 32 + (i % 8), i, 32, 40, i + 1, 40);
                room.setFlag(Flags.GREETING, "room");
                manager.addRegion(room);
            }
        }

        set = switch (kind) {
            case "wilderness" -> manager.getApplicableRegions(5000, 64, 5000);
            case "dense" -> manager.getApplicableRegions(36, 5, 36);
            default -> manager.getApplicableRegions(8, 64, 8);
        };
        if ("held".equals(kind)) {
            far.setFlag(Flags.TNT, State.DENY);
        }
        stranger = UUID.randomUUID();
    }

    private static ProtectedCuboidRegion cuboid(final String id, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(x1, y1, z1), BlockVector3.at(x2, y2, z2));
    }

    @Benchmark
    public State pvp() {
        return set.queryState(Flags.PVP, stranger);
    }

    @Benchmark
    public State use() {
        return set.queryState(Flags.USE, stranger);
    }

    @Benchmark
    public Object greeting() {
        return set.queryValue(Flags.GREETING, stranger);
    }

    @Benchmark
    public boolean canBuild() {
        return set.canBuild(stranger);
    }

    @Benchmark
    public boolean blockedCommand() {
        return set.flagSetContains(Flags.BLOCKED_CMDS, "/home", stranger);
    }
}
