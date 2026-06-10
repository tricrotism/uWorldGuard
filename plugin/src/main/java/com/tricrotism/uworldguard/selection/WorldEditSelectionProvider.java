package com.tricrotism.uworldguard.selection;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Selection backed by WorldEdit's own selection (//pos1, //pos2, //wand). Only constructed
 * when WorldEdit is installed — see {@link SelectionService} — so its classes never load
 * otherwise.
 */
@NullMarked
public final class WorldEditSelectionProvider implements SelectionProvider {

    private final WorldEditPlugin worldEdit;

    public WorldEditSelectionProvider(final WorldEditPlugin worldEdit) {
        this.worldEdit = worldEdit;
    }

    @Override
    public @Nullable Selection getSelection(final Player player) {
        final LocalSession session = worldEdit.getSession(player);
        try {
            final Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            final com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
            final com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();
            return new Selection(player.getWorld(),
                BlockVector3.at(min.x(), min.y(), min.z()),
                BlockVector3.at(max.x(), max.y(), max.z()));
        } catch (final IncompleteRegionException e) {
            return null;
        }
    }

    @Override
    public @Nullable List<BlockVector3> getPolygon(final Player player) {
        final LocalSession session = worldEdit.getSession(player);
        try {
            final Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            if (!(region instanceof Polygonal2DRegion poly)) {
                return null;
            }
            final List<BlockVector3> points = new ArrayList<>();
            for (final BlockVector2 p : poly.getPoints()) {
                points.add(BlockVector3.at(p.x(), 0, p.z()));
            }
            return points;
        } catch (final IncompleteRegionException e) {
            return null;
        }
    }
}
