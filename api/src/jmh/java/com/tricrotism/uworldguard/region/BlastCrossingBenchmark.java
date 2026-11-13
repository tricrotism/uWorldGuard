package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Nanoseconds to find the regions a blast's box crosses, for a box wider than the four chunks a
 * power 4 blast stays within: a bed, an end crystal, a charged creeper or a wither in open ground.
 *
 * <p>Run with {@code gradlew :api:jmh -Pjmh.args=BlastCrossingBenchmark}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BlastCrossingBenchmark {

    private static final int BOXES = 256;
    private static final int BLAST_WIDTH = 30;

    @Param({"200", "2000", "20000"})
    public int regions;

    private RegionManager manager;
    private int[][] boxes;
    private int next;

    @Setup
    public void setUp() {
        final Random random = new Random(1);
        final int side = (int) (Math.sqrt(regions) * 100);
        manager = new RegionManager();
        for (int i = 0; i < regions; i++) {
            final int x = random.nextInt(side);
            final int z = random.nextInt(side);
            manager.addRegion(new ProtectedCuboidRegion("claim" + i,
                BlockVector3.at(x, 0, z), BlockVector3.at(x + 40, 255, z + 40)));
        }
        boxes = new int[BOXES][];
        for (int i = 0; i < BOXES; i++) {
            final int x = random.nextInt(side);
            final int z = random.nextInt(side);
            boxes[i] = new int[]{x, 50, z, x + BLAST_WIDTH, 80, z + BLAST_WIDTH};
        }
    }

    @Benchmark
    public Object crossing() {
        final int i = next;
        next = (i + 1) & (BOXES - 1);
        return manager.crossing(boxes[i]);
    }
}
