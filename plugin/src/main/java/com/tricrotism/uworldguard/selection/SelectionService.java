package com.tricrotism.uworldguard.selection;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Resolves a player's selection from WorldEdit when it is installed, falling back to the
 * built-in {@link WandSelectionProvider} otherwise. The wand provider (a listener) is only
 * created and registered in the fallback case.
 */
@NullMarked
public final class SelectionService implements SelectionProvider {

    private final SelectionProvider delegate;
    private final @Nullable WandSelectionProvider wand;

    public SelectionService(final Plugin plugin, final Settings settings) {
        final Plugin we = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        if (we instanceof WorldEditPlugin worldEdit) {
            this.delegate = new WorldEditSelectionProvider(worldEdit);
            this.wand = null;
            plugin.getLogger().info("Using WorldEdit for region selection.");
        } else {
            final WandSelectionProvider provider = new WandSelectionProvider(settings.wandItem());
            this.delegate = provider;
            this.wand = provider;
            plugin.getLogger().info("WorldEdit not found; using the built-in selection wand ("
                + settings.wandItem().name() + ").");
        }
    }

    /**
     * The wand listener to register, or {@code null} when WorldEdit handles selection.
     */
    public @Nullable WandSelectionProvider wandListener() {
        return wand;
    }

    @Override
    public @Nullable Selection getSelection(final Player player) {
        return delegate.getSelection(player);
    }

    @Override
    public @Nullable List<BlockVector3> getPolygon(final Player player) {
        return delegate.getPolygon(player);
    }
}
