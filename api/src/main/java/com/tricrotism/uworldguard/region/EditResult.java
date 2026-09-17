package com.tricrotism.uworldguard.region;

import org.jspecify.annotations.NullMarked;

/**
 * What a {@link RegionEditor} call did.
 */
@NullMarked
public enum EditResult {

    /**
     * The edit was made and will be saved.
     */
    APPLIED,

    /**
     * The region already looked like that, so nothing was asked of other plugins and nothing is saved.
     */
    UNCHANGED,

    /**
     * A listener cancelled the edit's event. The actor, if there was one, has been told why.
     */
    CANCELLED,

    /**
     * The region, or the region named as its parent, is not in this editor's world.
     */
    NOT_FOUND,

    /**
     * A region with that id already exists in this world.
     */
    ALREADY_EXISTS,

    /**
     * The edit can never be made: an id outside {@link ProtectedRegion#isValidId}, a parent that
     * would create a cycle, or a shape change or removal aimed at the global region.
     */
    INVALID;

    public boolean isApplied() {
        return this == APPLIED;
    }
}
