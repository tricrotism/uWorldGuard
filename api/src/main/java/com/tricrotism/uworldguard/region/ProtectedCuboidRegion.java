package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;

/**
 * Axis-aligned box defined by two opposite corners.
 */
@NullMarked
public final class ProtectedCuboidRegion extends ProtectedRegion {

    private final BlockVector3 min;
    private final BlockVector3 max;

    public ProtectedCuboidRegion(final String id, final BlockVector3 a, final BlockVector3 b) {
        super(id);
        this.min = a.min(b);
        this.max = a.max(b);
    }

    @Override
    public RegionType getType() {
        return RegionType.CUBOID;
    }

    @Override
    public boolean contains(final int x, final int y, final int z) {
        return x >= min.x() && x <= max.x()
            && y >= min.y() && y <= max.y()
            && z >= min.z() && z <= max.z();
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
