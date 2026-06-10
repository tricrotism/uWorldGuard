package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.region.RegionManager;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence backend for regions, one logical store per world. Implementations must be
 * safe to call off the main thread (loading/saving is done on the async scheduler).
 * The default is {@link YamlRegionStore}; a SQL backend is an optional, disabled-by-default
 * implementation behind this same interface.
 */
@NullMarked
public interface RegionStore {

    /**
     * Load all regions for a world into {@code manager}.
     */
    void load(String worldName, RegionManager manager) throws Exception;

    /**
     * Persist all regions currently in {@code manager}.
     */
    void save(String worldName, RegionManager manager) throws Exception;
}
