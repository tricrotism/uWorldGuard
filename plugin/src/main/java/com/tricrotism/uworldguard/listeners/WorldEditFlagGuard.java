package com.tricrotism.uworldguard.listeners;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * Enforces the worldedit flag: WorldEdit operations by a non-bypassing player are blocked block-by-
 * block inside any region where worldedit=DENY. Only constructed when WorldEdit is installed (see
 * {@code UWorldGuard}), so its classes never load otherwise.
 *
 * <p>The wrap is installed once per edit session at the change stage, and only when a region in the
 * edited world uses the flag and the actor is a non-bypassing player. A world without the flag, or an
 * admin with bypass, pays nothing: the flag defaults to allow, so skipping the wrap there answers
 * exactly what every block would have. The world's manager is resolved once per session rather than
 * once per block, which on a million-block {@code //set} is a million map lookups saved.
 */
@NullMarked
public final class WorldEditFlagGuard {

    private final RegionContainerImpl container;

    public WorldEditFlagGuard(final RegionContainerImpl container) {
        this.container = container;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    /**
     * Leaves WorldEdit's event bus. That bus belongs to WorldEdit, not Bukkit, so the
     * {@code HandlerList} sweep Paper runs when a plugin disables does not reach it: without this a
     * reload leaves the old guard subscribed, still enforcing against the container it captured and
     * holding this plugin's classloader alive.
     */
    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(final EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        if (event.getWorld() == null || !container.anyRegionUses(Flags.WORLDEDIT)) {
            return;
        }
        final Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) {
            return;
        }
        final UUID uuid = actor.getUniqueId();
        final Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (player != null && Bypass.has(player)) {
            return;
        }
        final World world = BukkitAdapter.adapt(event.getWorld());
        final RegionManager manager = container.get(world);
        if (manager == null || !manager.anyRegionUses(Flags.WORLDEDIT)) {
            return;
        }
        event.setExtent(new AbstractDelegateExtent(event.getExtent()) {
            @Override
            public <B extends BlockStateHolder<B>> boolean setBlock(final BlockVector3 pos, final B block)
                throws WorldEditException {
                if (!manager.getApplicableRegions(pos.x(), pos.y(), pos.z())
                    .testState(Flags.WORLDEDIT, uuid)) {
                    return false;
                }
                return super.setBlock(pos, block);
            }
        });
    }
}
