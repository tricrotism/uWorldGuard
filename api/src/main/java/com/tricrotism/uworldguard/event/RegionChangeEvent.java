package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.region.ProtectedRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A deliberate edit to a region, fired <em>before</em> it is applied so a listener can veto it.
 * Subclasses cover creation, redefinition, removal, flag changes and membership changes.
 *
 * <p>Cancelling stops the edit and nothing is written. Set a {@link #setCancelMessage(Component)
 * cancel message} to say why, otherwise the actor is told only that another plugin refused it.
 *
 * <p>These report an edit an operator made through a command or a menu. Loading a world does not
 * fire them, since a load is not an edit and there would be nothing to veto, and neither does an
 * import or a direct call on {@code RegionManager} or {@code ProtectedRegion}. A plugin editing
 * regions through the API that wants other plugins to have a say fires the event itself first.
 *
 * <p>Threading: each fires on the thread that performs the edit, and {@link #isAsynchronous()}
 * follows it, so constructing one off a tick thread is always legal to fire. Menu clicks and most
 * commands arrive on the region thread that owns the actor. Membership edits that resolve a typed
 * name do so off-thread, because that lookup reads player data from disk. Nothing beyond the
 * event's own accessors is safe to touch without hopping to the thread that owns it.
 */
@NullMarked
public abstract class RegionChangeEvent extends Event implements Cancellable {

    private final World world;
    private final ProtectedRegion region;
    private final @Nullable CommandSender actor;

    private boolean cancelled;
    private @Nullable Component cancelMessage;

    /**
     * Paper refuses to fire an event whose declared threading disagrees with the calling thread, in
     * both directions, and a membership edit reaches here from a menu click on a tick thread as well
     * as from a name lookup on an async one. Deciding at construction is what keeps every caller
     * legal.
     */
    protected RegionChangeEvent(
        final World world, final ProtectedRegion region, final @Nullable CommandSender actor
    ) {
        super(!Bukkit.isPrimaryThread());
        this.world = world;
        this.region = region;
        this.actor = actor;
    }

    /**
     * The world whose regions are being edited.
     */
    public World getWorld() {
        return world;
    }

    /**
     * The region the edit applies to. For a creation this is the region about to be added, which is
     * not in the world yet; for everything else it is the region as it stands now, before the edit.
     */
    public ProtectedRegion getRegion() {
        return region;
    }

    /**
     * Who asked for the edit, or {@code null} when it came from the API rather than a command.
     */
    public @Nullable CommandSender getActor() {
        return actor;
    }

    /**
     * Why the edit was refused, shown to the actor in place of the generic refusal. {@code null}
     * unless a listener set one.
     */
    public @Nullable Component getCancelMessage() {
        return cancelMessage;
    }

    public void setCancelMessage(final @Nullable Component cancelMessage) {
        this.cancelMessage = cancelMessage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }
}
