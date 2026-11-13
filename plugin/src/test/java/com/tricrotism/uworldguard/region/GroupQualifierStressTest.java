package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.*;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group qualifiers against a plain reference model: random worlds, random edits, and sets held across
 * those edits, compared answer by answer. Then the same sets read from many threads while another
 * thread flips qualifiers on and off.
 */
class GroupQualifierStressTest {

    private static final StateFlag[] STATES = {Flags.PVP, Flags.USE, Flags.BUILD};
    private static final RegionGroup[] GROUPS = RegionGroup.values();
    private static final String[] COMMANDS = {"/home", "/spawn", "/tpa"};

    private final UUID[] players = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};

    @Test
    void randomWorldsMatchTheReferenceBeforeAndAfterEdits() {
        for (int seed = 0; seed < 2000; seed++) {
            final Random random = new Random(seed);
            final RegionManager manager = new RegionManager();
            final GlobalProtectedRegion global = new GlobalProtectedRegion();
            randomFlags(random, global);
            manager.addRegion(global);
            final List<ProtectedRegion> regions = new ArrayList<>();
            final int count = random.nextInt(10) == 0 ? 70 + random.nextInt(20) : 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                final int x = random.nextInt(24);
                final int z = random.nextInt(24);
                final ProtectedCuboidRegion region = new ProtectedCuboidRegion("r" + i,
                    BlockVector3.at(x, random.nextInt(32), z),
                    BlockVector3.at(x + random.nextInt(12), 32 + random.nextInt(32), z + random.nextInt(12)));
                region.setPriority(random.nextInt(4));
                if (!regions.isEmpty() && random.nextInt(4) == 0) {
                    region.setParent(regions.get(random.nextInt(regions.size())));
                }
                for (final UUID player : players) {
                    final int role = random.nextInt(4);
                    if (role == 1) {
                        region.getMembers().addPlayer(player);
                    } else if (role == 2) {
                        region.getOwners().addPlayer(player);
                    }
                }
                randomFlags(random, region);
                manager.addRegion(region);
                regions.add(region);
            }

            final List<int[]> points = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                points.add(new int[]{random.nextInt(48) - 8, random.nextInt(64), random.nextInt(48) - 8});
            }
            final List<ApplicableRegionSet> held = new ArrayList<>();
            for (final int[] p : points) {
                final ApplicableRegionSet set = manager.getApplicableRegions(p[0], p[1], p[2]);
                assertEquals(containing(regions, p), new HashSet<>(set.getRegions()), "seed " + seed);
                checkAll(seed, "before", set, global);
                held.add(set);
            }

            for (int edit = 0; edit < 6; edit++) {
                final ProtectedRegion target = random.nextInt(5) == 0 ? global : regions.get(random.nextInt(regions.size()));
                if (random.nextBoolean()) {
                    final StateFlag flag = STATES[random.nextInt(STATES.length)];
                    target.setFlagGroup(flag, random.nextInt(3) == 0 ? null : GROUPS[random.nextInt(GROUPS.length)]);
                } else {
                    randomFlags(random, target);
                }
            }

            for (int i = 0; i < points.size(); i++) {
                final int[] p = points.get(i);
                checkAll(seed, "held", held.get(i), global);
                checkAll(seed, "after", manager.getApplicableRegions(p[0], p[1], p[2]), global);
            }
        }
    }

    @Test
    void readersNeverSeeAnotherPlayersAnswerWhileQualifiersFlip() throws InterruptedException {
        final RegionManager manager = new RegionManager();
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.PVP, State.ALLOW);
        manager.addRegion(global);
        final UUID member = players[0];
        final UUID stranger = players[1];
        final ProtectedCuboidRegion town = new ProtectedCuboidRegion("town", BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        town.getMembers().addPlayer(member);
        town.setFlag(Flags.PVP, State.DENY);
        town.setFlag(Flags.BLOCKED_CMDS, Set.of("/home"));
        manager.addRegion(town);
        final ProtectedCuboidRegion far = new ProtectedCuboidRegion("far", BlockVector3.at(5000, 0, 5000), BlockVector3.at(5010, 255, 5010));
        far.setFlag(Flags.USE, State.DENY);
        manager.addRegion(far);
        final ApplicableRegionSet held = manager.getApplicableRegions(8, 64, 8);

        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<@Nullable String> failure = new AtomicReference<>();
        final int readers = Math.max(4, Runtime.getRuntime().availableProcessors() - 1);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < readers; t++) {
            final boolean useHeld = t % 2 == 0;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    return;
                }
                long reads = 0;
                while (running.get() && failure.get() == null) {
                    final ApplicableRegionSet set = useHeld ? held : manager.getApplicableRegions(8, 64, 8);
                    if (set.queryState(Flags.PVP, stranger) != State.DENY) {
                        failure.compareAndSet(null, "stranger escaped the pvp deny after " + reads + " reads");
                    }
                    if (!set.flagSetContains(Flags.BLOCKED_CMDS, "/home", stranger)) {
                        failure.compareAndSet(null, "stranger escaped the command block after " + reads + " reads");
                    }
                    if (set.canBuild(stranger) || !set.canBuild(member)) {
                        failure.compareAndSet(null, "build answer flipped after " + reads + " reads");
                    }
                    final State forMember = set.queryState(Flags.PVP, member);
                    if (forMember == null) {
                        failure.compareAndSet(null, "null state");
                    }
                    reads++;
                }
            }));
        }

        start.countDown();
        final long end = System.nanoTime() + 2_000_000_000L;
        for (int i = 0; System.nanoTime() < end; i++) {
            town.setFlagGroup(Flags.PVP, i % 2 == 0 ? RegionGroup.NON_MEMBERS : null);
            town.setFlagGroup(Flags.BLOCKED_CMDS, i % 3 == 0 ? RegionGroup.NON_MEMBERS : null);
            far.setFlagGroup(Flags.USE, i % 5 == 0 ? RegionGroup.MEMBERS : null);
        }
        town.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        running.set(false);
        for (final Thread thread : threads) {
            thread.join();
        }

        assertNull(failure.get());
        assertEquals(State.ALLOW, held.queryState(Flags.PVP, member), "the last qualifier reaches the held set");
        assertEquals(State.ALLOW, manager.getApplicableRegions(8, 64, 8).queryState(Flags.PVP, member));
        assertEquals(State.DENY, held.queryState(Flags.PVP, stranger));
    }

    private void randomFlags(final Random random, final ProtectedRegion region) {
        for (final StateFlag flag : STATES) {
            final int roll = random.nextInt(4);
            region.setFlag(flag, roll == 0 ? State.ALLOW : roll == 1 ? State.DENY : null);
            if (random.nextInt(3) == 0) {
                region.setFlagGroup(flag, GROUPS[random.nextInt(GROUPS.length)]);
            }
        }
        region.setFlag(Flags.GREETING, random.nextBoolean() ? "hi " + random.nextInt(3) : null);
        if (random.nextInt(3) == 0) {
            region.setFlagGroup(Flags.GREETING, GROUPS[random.nextInt(GROUPS.length)]);
        }
        final Set<String> commands = new HashSet<>();
        for (final String command : COMMANDS) {
            if (random.nextInt(3) == 0) {
                commands.add(command);
            }
        }
        region.setFlag(Flags.BLOCKED_CMDS, commands.isEmpty() ? null : commands);
        if (random.nextInt(3) == 0) {
            region.setFlagGroup(Flags.BLOCKED_CMDS, GROUPS[random.nextInt(GROUPS.length)]);
        }
    }

    private static Set<ProtectedRegion> containing(final List<ProtectedRegion> regions, final int[] p) {
        final Set<ProtectedRegion> out = new HashSet<>();
        for (final ProtectedRegion region : regions) {
            if (region.contains(p[0], p[1], p[2])) {
                out.add(region);
            }
        }
        return out;
    }

    private void checkAll(final int seed, final String phase, final ApplicableRegionSet set, final GlobalProtectedRegion global) {
        final List<ProtectedRegion> regions = new ArrayList<>(set.getRegions());
        regions.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed());
        final List<@Nullable UUID> subjects = new ArrayList<>(List.of(players));
        subjects.add(null);
        for (final UUID subject : subjects) {
            final String where = "seed " + seed + " " + phase + " subject " + subject;
            for (final StateFlag flag : STATES) {
                assertEquals(expectedState(regions, global, flag, subject), set.queryState(flag, subject),
                    () -> where + " " + flag.getName() + " " + dump(regions, global, flag, subject));
            }
            assertTrue(expectedValues(regions, global, Flags.GREETING, subject).contains(set.queryValue(Flags.GREETING, subject)),
                where + " greeting " + set.queryValue(Flags.GREETING, subject));
            for (final String command : COMMANDS) {
                assertEquals(expectedContains(regions, global, command, subject, true),
                    set.flagSetContains(Flags.BLOCKED_CMDS, command, subject), where + " " + command);
                assertEquals(expectedContains(regions, global, command, subject, false),
                    set.flagSetContains(Flags.BLOCKED_CMDS, command), where + " plain " + command);
            }
            assertEquals(expectedBuild(regions, global, subject), set.canBuild(subject), where + " canBuild");
        }
    }

    private static String dump(
        final List<ProtectedRegion> regions, final ProtectedRegion global, final Flag<?> flag, final @Nullable UUID subject
    ) {
        final StringBuilder out = new StringBuilder();
        final List<ProtectedRegion> all = new ArrayList<>(regions);
        all.add(global);
        for (final ProtectedRegion region : all) {
            out.append("\n  ").append(region.getId()).append(" p=").append(region.getPriority())
                .append(" parent=").append(region.getParent() == null ? "-" : region.getParent().getId())
                .append(" value=").append(region.getFlag(flag)).append(" own=").append(region.getFlags().get(flag))
                .append(" group=").append(region.getFlagGroup(flag))
                .append(" assoc=").append(subject == null ? "-" : region.getAssociation(subject));
        }
        return out.toString();
    }

    private static boolean applies(final ProtectedRegion region, final Flag<?> flag, final @Nullable UUID subject) {
        return region.getFlagGroup(flag).contains(subject == null ? null : region.getAssociation(subject));
    }

    private static State expectedState(
        final List<ProtectedRegion> regions, final ProtectedRegion global, final StateFlag flag, final @Nullable UUID subject
    ) {
        Integer best = null;
        State result = null;
        for (final ProtectedRegion region : regions) {
            if (best != null && region.getPriority() < best) {
                break;
            }
            final State v = region.getFlag(flag);
            if (v != null && applies(region, flag, subject)) {
                if (best == null) {
                    best = region.getPriority();
                    result = v;
                } else if (v == State.DENY) {
                    result = State.DENY;
                }
            }
        }
        if (best == null) {
            result = global.getFlag(flag) != null && applies(global, flag, subject) ? global.getFlag(flag) : null;
        }
        return result != null ? result : flag.getDefault();
    }

    /**
     * Every value the top tier could answer with, since regions of equal priority have no set order.
     */
    private static <T> Set<@Nullable T> expectedValues(
        final List<ProtectedRegion> regions, final ProtectedRegion global, final Flag<T> flag, final @Nullable UUID subject
    ) {
        final Set<@Nullable T> out = new HashSet<>();
        Integer best = null;
        for (final ProtectedRegion region : regions) {
            if (best != null && region.getPriority() < best) {
                break;
            }
            final T v = region.getFlag(flag);
            if (v != null && applies(region, flag, subject)) {
                best = region.getPriority();
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            out.add(global.getFlag(flag) != null && applies(global, flag, subject) ? global.getFlag(flag) : null);
        }
        return out;
    }

    private static boolean expectedContains(
        final List<ProtectedRegion> regions, final ProtectedRegion global, final String command,
        final @Nullable UUID subject, final boolean groups
    ) {
        final List<ProtectedRegion> all = new ArrayList<>(regions);
        all.add(global);
        for (final ProtectedRegion region : all) {
            final Set<String> set = region.getFlag(Flags.BLOCKED_CMDS);
            if (set != null && set.contains(command) && (!groups || applies(region, Flags.BLOCKED_CMDS, subject))) {
                return true;
            }
        }
        return false;
    }

    private static boolean expectedBuild(
        final List<ProtectedRegion> regions, final ProtectedRegion global, final @Nullable UUID subject
    ) {
        if (regions.isEmpty()) {
            final State g = global.getFlag(Flags.BUILD);
            return g == null || !applies(global, Flags.BUILD, subject) || g != State.DENY;
        }
        final int top = regions.getFirst().getPriority();
        State explicit = null;
        boolean member = false;
        for (final ProtectedRegion region : regions) {
            if (region.getPriority() != top) {
                break;
            }
            final State v = region.getFlag(Flags.BUILD);
            if (v != null && applies(region, Flags.BUILD, subject)) {
                explicit = explicit == State.DENY ? State.DENY : v;
            }
            if (subject != null && region.isMember(subject)) {
                member = true;
            }
        }
        return explicit != null ? explicit == State.ALLOW : member;
    }
}
