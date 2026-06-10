package com.tricrotism.uworldguard.region;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.jspecify.annotations.NullMarked;

/**
 * The implicit lowest-priority region covering an entire world. Its flags apply
 * everywhere no other region overrides them. Conventionally named {@code __global__}.
 */
@NullMarked
public final class GlobalProtectedRegion extends ProtectedRegion {

    public static final String ID = "__global__";

    private static final BlockVector3 MIN = BlockVector3.at(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    private static final BlockVector3 MAX = BlockVector3.at(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    public GlobalProtectedRegion() {
        super(ID);
    }

    @Override
    public RegionType getType() {
        return RegionType.GLOBAL;
    }

    @Override
    public boolean contains(final int x, final int y, final int z) {
        return true;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return MIN;
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return MAX;
    }
}
