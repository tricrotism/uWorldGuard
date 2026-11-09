package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Convenience facade for location-based queries. Stateless over a {@link RegionContainer},
 * so a single instance is safe to share across threads.
 *
 * <p>The {@code (World, int, int, int)} method is the primitive entry point; the {@link Block},
 * {@link Entity}, and {@link Location} overloads funnel into it from coordinates the caller
 * already holds, so a query allocates nothing for the position. Prefer the {@code Block}/{@code
 * Entity} overloads in hot paths over {@code block.getLocation()} / {@code entity.getLocation()},
 * which allocate a throwaway {@link Location}.
 */
@NullMarked
public final class RegionQuery {

    private static final ApplicableRegionSet EMPTY = new ApplicableRegionSet(List.of(), null);

    /**
     * The most slots a blast's axis tables may span, about a 340 block wide box. A blast spread wider
     * than that tests its crossing regions per block instead.
     */
    private static final int AXIS_SLOTS = 1024;

    /**
     * Distinct cells judged before a blast's blocks are. A blast across one claim edge has two, a
     * corner four. More than this and the blocks are judged as they come.
     */
    private static final int CELLS = 8;

    private static final byte KEEP = 1;
    private static final byte DROP = 2;

    /**
     * Blocks a thread's kept position array may hold. A power 4 blast breaks a few hundred.
     */
    private static final int KEPT_COORDS = 4096;

    private static final ThreadLocal<int[]> COORDS = new ThreadLocal<>();

    private final RegionContainer container;

    public RegionQuery(final RegionContainer container) {
        this.container = container;
    }

    public ApplicableRegionSet getApplicableRegions(final World world, final int x, final int y, final int z) {
        final RegionManager manager = container.get(world);
        if (manager == null) {
            return EMPTY;
        }
        return manager.getApplicableRegions(x, y, z);
    }

    /**
     * Whether any region in {@code world} sets {@code flag} — one map lookup and a bitset test, with
     * no position involved. A {@code false} guarantees every query for that flag in that world
     * resolves to the flag's default, because the global region and every parent are themselves
     * regions in that world's manager.
     *
     * <p>This is the gate for handlers on high-frequency events. A listener enforcing a flag that
     * defaults to {@code ALLOW} does nothing when nobody has set it, so testing this first lets the
     * event return before a region set is resolved or allocated. Do not gate on it for a flag whose
     * default is {@code DENY} — there the unset case is the one that acts.
     */
    public boolean usesFlag(final World world, final Flag<?> flag) {
        final RegionManager manager = container.get(world);
        return manager != null && manager.anyRegionUses(flag);
    }

    /**
     * {@link #usesFlag} for a group of flags in one test, {@code mask} from {@code Flags.mask}. Lets a
     * handler that picks its flag from a block's type skip reading the block when none is set.
     */
    public boolean usesAnyFlag(final World world, final long[] mask) {
        final RegionManager manager = container.get(world);
        return manager != null && manager.anyRegionUsesAny(mask);
    }

    public ApplicableRegionSet getApplicableRegions(final Location location) {
        return getApplicableRegions(location.getWorld(),
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public ApplicableRegionSet getApplicableRegions(final Block block) {
        return getApplicableRegions(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public ApplicableRegionSet getApplicableRegions(final Entity entity) {
        return getApplicableRegions(entity.getWorld(),
            Location.locToBlock(entity.getX()), Location.locToBlock(entity.getY()), Location.locToBlock(entity.getZ()));
    }

    /**
     * Whether the player (or {@code null} for an unknown actor) may build at the location.
     */
    public boolean testBuild(final Location location, final @Nullable Player player) {
        return getApplicableRegions(location).canBuild(player != null ? player.getUniqueId() : null);
    }

    public boolean testState(final Location location, final StateFlag flag) {
        return getApplicableRegions(location).testState(flag);
    }

    public boolean testState(final Block block, final StateFlag flag) {
        return getApplicableRegions(block).testState(flag);
    }

    public boolean testState(final Entity entity, final StateFlag flag) {
        return getApplicableRegions(entity).testState(flag, entity instanceof Player player ? player.getUniqueId() : null);
    }

    /**
     * Test a state flag at a block as it applies to {@code player} — use this over the plain
     * {@link #testState(Block, StateFlag)} whenever the acting player is known, so a value qualified
     * to members or non-members resolves against them rather than defaulting to non-member.
     */
    public boolean testState(final Block block, final StateFlag flag, final @Nullable Player player) {
        return getApplicableRegions(block).testState(flag, player == null ? null : player.getUniqueId());
    }

    /**
     * Test a state flag at a location as it applies to {@code player}. Prefer the {@link Block}
     * overload where a block is already in hand — this one exists for events that only expose a
     * {@link Location}.
     */
    public boolean testState(final Location location, final StateFlag flag, final @Nullable Player player) {
        return getApplicableRegions(location).testState(flag, player == null ? null : player.getUniqueId());
    }

    /**
     * Drops every block where {@link #testState(Block, StateFlag)} is false. Cheapest answer first:
     * a world where no region sets {@code flag} reads the flag's default everywhere, and a storage
     * backend that proves {@code flag} reads the same over the blocks' bounding box answers for all
     * of them. Otherwise the regions touching the box are fetched once and each block is tested
     * against that short list rather than looked up again.
     */
    public void removeDenied(final World world, final List<Block> blocks, final StateFlag flag) {
        if (blocks.isEmpty()) {
            return;
        }
        final RegionManager manager = container.get(world);
        if (manager != null && !manager.anyRegionUses(flag)) {
            if (flag.getDefault() != State.ALLOW) {
                blocks.clear();
            }
            return;
        }
        if (manager != null) {
            final int[] xyz = coords(blocks.size());
            final int[] box = bounds(blocks, xyz);
            final State across = manager.resolvesBoxes() ?
                manager.stateAcross(box[0], box[1], box[2], box[3], box[4], box[5], flag) :
                null;
            if (across == State.ALLOW) {
                return;
            }
            if (across == State.DENY) {
                blocks.clear();
                return;
            }
            filter(manager, blocks, xyz, box, new Verdict(flag, false, false, null, null));
            return;
        }
        blocks.removeIf(block -> !testState(block, flag));
    }

    /**
     * Drops every block an explosion may not break, as WorldGuard judges it: where {@code flag}
     * denies the blast, where {@code block-break} is denied, and otherwise wherever its cause may not
     * build. A blast with no player behind it acts as a member of {@code source}, the regions where it
     * started, so it breaks blocks in its own claim but not in a neighbour's. A {@code null} source is a
     * member nowhere.
     *
     * @param flagOpens whether an explicit {@code allow} of {@code flag} lets anyone's blast through,
     *                  as {@code tnt} does in WorldGuard
     */
    public void removeUnbreakable(
        final World world, final List<Block> blocks, final StateFlag flag, final boolean flagOpens,
        final @Nullable UUID breaker, final @Nullable ApplicableRegionSet source
    ) {
        if (blocks.isEmpty()) {
            return;
        }
        final RegionManager manager = container.get(world);
        if (manager == null) {
            return;
        }
        final int[] xyz = coords(blocks.size());
        filter(manager, blocks, xyz, bounds(blocks, xyz), new Verdict(flag, true, flagOpens, breaker, source));
    }

    /**
     * Keeps the blocks {@code verdict} allows and compacts the list in place. A region covering the
     * whole box applies to every block, so only the crossing ones are tested per block, and blocks
     * covered by the same regions share one answer.
     *
     * <p>Each pattern is judged on the manager's set at its first block. That set is exactly the
     * covering regions plus the crossing ones the pattern names.
     *
     * <p>A crossing region's bit is laid along each axis of the box by its bounding box, so a block's
     * pattern is three array reads. That is exact for a cuboid. Other shapes still test the blocks
     * inside their box.
     */
    private static void filter(
        final RegionManager manager, final List<Block> blocks, final int[] xyz, final int[] box, final Verdict verdict
    ) {
        final ProtectedRegion[] crossing = manager.crossing(box);
        if (crossing == null) {
            if (!verdict.test(manager.getApplicableRegions(box[0], box[1], box[2]))) {
                blocks.clear();
            }
            return;
        }
        final int c = crossing.length;
        final byte[] answers = c <= 6 ? new byte[1 << c] : null;
        final boolean memo = c < Long.SIZE;
        final int x0 = box[0];
        final int y0 = box[1];
        final int z0 = box[2];
        final int w = box[3] - x0 + 1;
        final int h = box[4] - y0 + 1;
        final int d = box[5] - z0 + 1;
        final long[] axes = memo && (long) w + h + d <= AXIS_SLOTS ? new long[w + h + d] : null;
        long shaped = memo ? -1L >>> (Long.SIZE - c) : 0L;
        if (axes != null) {
            for (int j = 0; j < c; j++) {
                final ProtectedRegion region = crossing[j];
                final long bit = 1L << j;
                final BlockVector3 min = region.getMinimumPoint();
                final BlockVector3 max = region.getMaximumPoint();
                lay(axes, 0, w, min.x() - x0, max.x() - x0, bit);
                lay(axes, w, h, min.y() - y0, max.y() - y0, bit);
                lay(axes, w + h, d, min.z() - z0, max.z() - z0, bit);
                if (region instanceof ProtectedCuboidRegion) {
                    shaped &= ~bit;
                }
            }
        }
        if (answers != null && axes != null && shaped == 0L) {
            final int agreed = judgeCells(manager, verdict, axes, answers, box, w, h);
            if (agreed == KEEP) {
                return;
            }
            if (agreed == DROP) {
                blocks.clear();
                return;
            }
        }
        long lastMask = -1L;
        boolean lastKept = false;
        final int size = blocks.size();
        int kept = 0;
        for (int b = 0; b < size; b++) {
            final int x = xyz[3 * b];
            final int y = xyz[3 * b + 1];
            final int z = xyz[3 * b + 2];
            long mask = axes != null ? axes[x - x0] & axes[w + y - y0] & axes[w + h + z - z0] : shaped;
            for (long test = mask & shaped; test != 0L; test &= test - 1) {
                final int j = Long.numberOfTrailingZeros(test);
                if (!crossing[j].contains(x, y, z)) {
                    mask &= ~(1L << j);
                }
            }
            final boolean keep;
            if (answers != null && answers[(int) mask] != 0) {
                keep = answers[(int) mask] == KEEP;
            } else if (memo && mask == lastMask) {
                keep = lastKept;
            } else {
                keep = verdict.test(manager.getApplicableRegions(x, y, z));
                if (answers != null) {
                    answers[(int) mask] = keep ? KEEP : DROP;
                }
                lastMask = mask;
                lastKept = keep;
            }
            if (keep) {
                if (kept != b) {
                    blocks.set(kept, blocks.get(b));
                }
                kept++;
            }
        }
        if (kept < size) {
            blocks.subList(kept, size).clear();
        }
    }

    /**
     * One blast's verdict for a block's regions: the flag alone, or the flag and then build rights.
     */
    private static final class Verdict {

        private final StateFlag flag;
        private final boolean build;
        private final boolean flagOpens;
        private final @Nullable UUID breaker;
        private final @Nullable ApplicableRegionSet source;

        Verdict(
            final StateFlag flag, final boolean build, final boolean flagOpens,
            final @Nullable UUID breaker, final @Nullable ApplicableRegionSet source
        ) {
            this.flag = flag;
            this.build = build;
            this.flagOpens = flagOpens;
            this.breaker = breaker;
            this.source = source;
        }

        boolean test(final ApplicableRegionSet set) {
            if (!set.testState(flag)) {
                return false;
            }
            if (!build) {
                return true;
            }
            final State explicit = set.queryExplicitState(Flags.BLOCK_BREAK, breaker);
            if (explicit != null) {
                return explicit == State.ALLOW;
            }
            if (flagOpens && set.queryExplicitState(flag, breaker) == State.ALLOW) {
                return true;
            }
            if (set.canBuild(breaker)) {
                return true;
            }
            return breaker == null && source != null && set.overlaps(source);
        }
    }

    /**
     * With every crossing region a cuboid, the regions over a block depend only on which cell of the
     * box's grid it sits in. Judges each cell into {@code answers}, so when they all agree the blast
     * is answered without looking at a block again.
     *
     * @return {@link #KEEP} or {@link #DROP} when every cell agrees, or 0 when they differ or there
     * are more than {@link #CELLS} to judge
     */
    private static int judgeCells(
        final RegionManager manager, final Verdict verdict, final long[] axes, final byte[] answers,
        final int[] box, final int w, final int h
    ) {
        final int d = axes.length - w - h;
        int agreed = 0;
        int judged = 0;
        for (int x = 0; x < w; x++) {
            if (x > 0 && axes[x] == axes[x - 1]) {
                continue;
            }
            for (int y = 0; y < h; y++) {
                if (y > 0 && axes[w + y] == axes[w + y - 1]) {
                    continue;
                }
                for (int z = 0; z < d; z++) {
                    if (z > 0 && axes[w + h + z] == axes[w + h + z - 1]) {
                        continue;
                    }
                    final int mask = (int) (axes[x] & axes[w + y] & axes[w + h + z]);
                    if (answers[mask] == 0) {
                        if (++judged > CELLS) {
                            return 0;
                        }
                        answers[mask] = verdict.test(manager.getApplicableRegions(box[0] + x, box[1] + y, box[2] + z)) ? KEEP : DROP;
                    }
                    if (agreed == 0) {
                        agreed = answers[mask];
                    } else if (agreed != answers[mask]) {
                        return 0;
                    }
                }
            }
        }
        return agreed;
    }

    private static void lay(final long[] axes, final int offset, final int length, final int from, final int to, final long bit) {
        for (int i = Math.max(from, 0), end = Math.min(to, length - 1); i <= end; i++) {
            axes[offset + i] |= bit;
        }
    }

    /**
     * Each thread keeps its own scratch array, so Folia's region threads never share one. An array for
     * an outsized blast is used once and not kept.
     */
    private static int[] coords(final int size) {
        int[] xyz = COORDS.get();
        if (xyz == null || xyz.length < 3 * size) {
            xyz = new int[3 * size];
            if (size <= KEPT_COORDS) {
                COORDS.set(xyz);
            }
        }
        return xyz;
    }

    private static int[] bounds(final List<Block> blocks, final int[] xyz) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0, n = blocks.size(); i < n; i++) {
            final Block b = blocks.get(i);
            final int x = b.getX();
            final int y = b.getY();
            final int z = b.getZ();
            xyz[3 * i] = x;
            xyz[3 * i + 1] = y;
            xyz[3 * i + 2] = z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    public <T> @Nullable T queryValue(final Location location, final Flag<T> flag) {
        return getApplicableRegions(location).queryValue(flag);
    }

    public <T> @Nullable T queryValue(final Entity entity, final Flag<T> flag) {
        return entity instanceof Player player
            ? getApplicableRegions(entity).queryValue(flag, player.getUniqueId())
            : getApplicableRegions(entity).queryValue(flag);
    }
}
