package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

/**
 * A 2D polygon footprint (X/Z) extruded between a minimum and maximum Y.
 */
@NullMarked
public final class ProtectedPolygonRegion extends ProtectedRegion {

    private final int[] pointsX;
    private final int[] pointsZ;
    private final int minY;
    private final int maxY;
    private final BlockVector3 min;
    private final BlockVector3 max;

    public ProtectedPolygonRegion(final String id, final List<BlockVector3> points, final int minY, final int maxY) {
        super(id);
        if (points.size() < 3) {
            throw new IllegalArgumentException("Polygon needs at least 3 points");
        }
        final int n = points.size();
        this.pointsX = new int[n];
        this.pointsZ = new int[n];
        int loX = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            final BlockVector3 p = points.get(i);
            pointsX[i] = p.x();
            pointsZ[i] = p.z();
            loX = Math.min(loX, p.x());
            loZ = Math.min(loZ, p.z());
            hiX = Math.max(hiX, p.x());
            hiZ = Math.max(hiZ, p.z());
        }
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.min = BlockVector3.at(loX, this.minY, loZ);
        this.max = BlockVector3.at(hiX, this.maxY, hiZ);
    }

    @Override
    public RegionType getType() {
        return RegionType.POLYGON;
    }

    @Override
    public boolean contains(final int x, final int y, final int z) {
        if (y < minY || y > maxY) {
            return false;
        }
        // Even-odd ray casting in the X/Z plane.
        boolean inside = false;
        final int n = pointsX.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final int xi = pointsX[i];
            final int zi = pointsZ[i];
            final int xj = pointsX[j];
            final int zj = pointsZ[j];
            if ((zi > z) != (zj > z)) {
                final double crossX = (double) (xj - xi) * (z - zi) / (zj - zi) + xi;
                if (x < crossX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return min;
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return max;
    }

    /**
     * The footprint vertices (Y set to the region's minimum) — used for persistence.
     */
    public List<BlockVector3> getPoints() {
        final List<BlockVector3> points = new ArrayList<>(pointsX.length);
        for (int i = 0; i < pointsX.length; i++) {
            points.add(BlockVector3.at(pointsX[i], minY, pointsZ[i]));
        }
        return points;
    }
}
