package com.tricrotism.uworldguard.selection;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

/**
 * A completed cuboid selection: a world and two inclusive corners.
 */
@NullMarked
public record Selection(World world, BlockVector3 min, BlockVector3 max) {}