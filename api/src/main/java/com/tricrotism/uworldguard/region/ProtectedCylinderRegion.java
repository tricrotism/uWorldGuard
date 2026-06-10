package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;

/**
 * An elliptical footprint (X/Z) extruded between a minimum and maximum Y.
 */
@NullMarked
public final class ProtectedCylinderRegion extends ProtectedRegion {

    private final int centerX;
    private final int centerZ;
    private final int radiusX;
    private final int radiusZ;
    private final int minY;
    private final int maxY;
    private final double invRadiusX2;
    private final double invRadiusZ2;
    private final BlockVector3 min;
    private final BlockVector3 max;

    public ProtectedCylinderRegion(final String id, final int centerX, final int centerZ,
                                   final int radiusX, final int radiusZ, final int minY, final int maxY) {
        super(id);
        if (radiusX <= 0 || radiusZ <= 0) {
            throw new IllegalArgumentException("Cylinder radii must be positive");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.invRadiusX2 = 1.0 / ((double) radiusX * radiusX);
        this.invRadiusZ2 = 1.0 / ((double) radiusZ * radiusZ);
        this.min = BlockVector3.at(centerX - radiusX, this.minY, centerZ - radiusZ);
        this.max = BlockVector3.at(centerX + radiusX, this.maxY, centerZ + radiusZ);
    }

    @Override
    public RegionType getType() {
        return RegionType.CYLINDER;
    }

    @Override
    public boolean contains(final int x, final int y, final int z) {
        if (y < minY || y > maxY) {
            return false;
        }
        final double dx = x - centerX;
        final double dz = z - centerZ;
        return dx * dx * invRadiusX2 + dz * dz * invRadiusZ2 <= 1.0;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return min;
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return max;
    }
}
