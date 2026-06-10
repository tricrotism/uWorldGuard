package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;

/**
 * A 3D ellipsoid defined by a center and a per-axis radius.
 */
@NullMarked
public final class ProtectedSphereRegion extends ProtectedRegion {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double invRadiusX2;
    private final double invRadiusY2;
    private final double invRadiusZ2;
    private final BlockVector3 min;
    private final BlockVector3 max;

    public ProtectedSphereRegion(
        final String id, final int centerX, final int centerY, final int centerZ,
        final int radiusX, final int radiusY, final int radiusZ
    ) {
        super(id);
        if (radiusX <= 0 || radiusY <= 0 || radiusZ <= 0) {
            throw new IllegalArgumentException("Sphere radii must be positive");
        }
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.invRadiusX2 = 1.0 / ((double) radiusX * radiusX);
        this.invRadiusY2 = 1.0 / ((double) radiusY * radiusY);
        this.invRadiusZ2 = 1.0 / ((double) radiusZ * radiusZ);
        this.min = BlockVector3.at(centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
        this.max = BlockVector3.at(centerX + radiusX, centerY + radiusY, centerZ + radiusZ);
    }

    @Override
    public RegionType getType() {
        return RegionType.SPHERE;
    }

    @Override
    public boolean contains(final int x, final int y, final int z) {
        final double dx = x - centerX;
        final double dy = y - centerY;
        final double dz = z - centerZ;
        return dx * dx * invRadiusX2 + dy * dy * invRadiusY2 + dz * dz * invRadiusZ2 <= 1.0;
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
