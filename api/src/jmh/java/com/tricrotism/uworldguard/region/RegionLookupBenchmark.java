package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Nanoseconds per region lookup, per shape. {@code lookup} is the full path every protection check
 * takes (chunk cache, bounds, exact containment, building the set); {@code contains} is the shape's own
 * test alone. Query points sit in and around the shape's box, so both hits and near misses count.
 *
 * <p>Run with {@code gradlew :api:jmh}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RegionLookupBenchmark {

    private static final int POINTS = 1024;

    @Param({"wilderness", "cuboid", "polygon", "sphere", "polyhedron", "composite", "carved", "crowded"})
    public String shape;

    private RegionManager manager;
    private ProtectedRegion region;
    private int[] xs;
    private int[] ys;
    private int[] zs;
    private int next;

    @Setup
    public void setUp() {
        final Random random = new Random(1);
        manager = new RegionManager();
        for (int i = 0; i < 50; i++) {
            final int x = 500 + random.nextInt(4000);
            final int z = 500 + random.nextInt(4000);
            manager.addRegion(new ProtectedCuboidRegion("bg" + i,
                BlockVector3.at(x, 0, z), BlockVector3.at(x + 40, 255, z + 40)));
        }
        region = build(shape, random);
        manager.addRegion(region);
        if ("crowded".equals(shape)) {
            // three floors of sixteen rooms over one chunk, 49 candidates with the region itself
            for (int floor = 0; floor < 3; floor++) {
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        manager.addRegion(cuboid("room" + floor + "_" + x + "_" + z,
                            x, floor * 20, z, x + 3, floor * 20 + 19, z + 3));
                    }
                }
            }
        }

        final BlockVector3 lo = region.getMinimumPoint();
        final BlockVector3 hi = region.getMaximumPoint();
        xs = new int[POINTS];
        ys = new int[POINTS];
        zs = new int[POINTS];
        for (int i = 0; i < POINTS; i++) {
            xs[i] = lo.x() - 2 + random.nextInt(hi.x() - lo.x() + 5);
            ys[i] = lo.y() - 2 + random.nextInt(hi.y() - lo.y() + 5);
            zs[i] = lo.z() - 2 + random.nextInt(hi.z() - lo.z() + 5);
        }
    }

    private static ProtectedRegion build(final String shape, final Random random) {
        return switch (shape) {
            case "wilderness" -> new ProtectedCuboidRegion("far",
                BlockVector3.at(-9000, 0, -9000), BlockVector3.at(-8990, 10, -8990));
            case "cuboid" -> cuboid("r", 0, 0, 0, 64, 64, 64);
            case "crowded" -> cuboid("r", 0, 0, 0, 15, 63, 15);
            case "polygon" -> {
                final List<BlockVector3> points = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final double a = Math.PI * 2 * i / 8;
                    points.add(BlockVector3.at(32 + (int) (32 * Math.cos(a)), 0, 32 + (int) (32 * Math.sin(a))));
                }
                yield new ProtectedPolygonRegion("r", points, 0, 64);
            }
            case "sphere" -> new ProtectedSphereRegion("r", 32, 32, 32, 32, 32, 32);
            case "polyhedron" -> gem("r", 32, 32, 32, 32, random);
            case "composite" -> {
                final Map<String, ProtectedRegion> parts = new LinkedHashMap<>();
                parts.put("a", cuboid("a", 0, 0, 0, 20, 64, 20));
                parts.put("b", new ProtectedSphereRegion("b", 44, 32, 44, 20, 20, 20));
                parts.put("c", gem("c", 44, 32, 12, 12, random));
                yield new ProtectedCompositeRegion("r", parts);
            }
            case "carved" -> {
                final Map<String, ProtectedRegion> holes = new LinkedHashMap<>();
                holes.put("a", cuboid("a", 10, 10, 10, 20, 20, 20));
                holes.put("b", new ProtectedSphereRegion("b", 44, 32, 44, 10, 10, 10));
                holes.put("c", cuboid("c", 0, 0, 30, 64, 64, 34));
                yield new ProtectedCarvedRegion("r", cuboid("s", 0, 0, 0, 64, 64, 64), holes);
            }
            default -> throw new IllegalArgumentException(shape);
        };
    }

    private static ProtectedCuboidRegion cuboid(final String id, final int x1, final int y1, final int z1,
                                                final int x2, final int y2, final int z2) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(x1, y1, z1), BlockVector3.at(x2, y2, z2));
    }

    /**
     * Sixty points on a sphere, so the hull has on the order of a hundred faces.
     */
    private static ProtectedPolyhedronRegion gem(final String id, final int cx, final int cy, final int cz,
                                                 final int r, final Random random) {
        final List<BlockVector3> points = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final double u = random.nextDouble() * 2 - 1;
            final double t = random.nextDouble() * Math.PI * 2;
            final double s = Math.sqrt(1 - u * u);
            points.add(BlockVector3.at(cx + (int) (r * s * Math.cos(t)), cy + (int) (r * u), cz + (int) (r * s * Math.sin(t))));
        }
        return new ProtectedPolyhedronRegion(id, points);
    }

    @Benchmark
    public int lookup() {
        final int i = next;
        next = (i + 1) & (POINTS - 1);
        return manager.getApplicableRegions(xs[i], ys[i], zs[i]).size();
    }

    @Benchmark
    public boolean contains() {
        final int i = next;
        next = (i + 1) & (POINTS - 1);
        return region.contains(xs[i], ys[i], zs[i]);
    }
}
