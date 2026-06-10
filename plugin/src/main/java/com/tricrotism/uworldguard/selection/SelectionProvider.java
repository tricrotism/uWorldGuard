package com.tricrotism.uworldguard.selection;

import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Supplies a player's current selection, or {@code null} if none is set.
 */
@NullMarked
public interface SelectionProvider {

    /**
     * The bounding cuboid of the current selection.
     */
    @Nullable Selection getSelection(Player player);

    /**
     * The X/Z footprint of the current selection when it is a polygon, or {@code null} if
     * the backend cannot supply one (the built-in wand only does cuboids).
     */
    default @Nullable List<BlockVector3> getPolygon(final Player player) {
        return null;
    }
}
