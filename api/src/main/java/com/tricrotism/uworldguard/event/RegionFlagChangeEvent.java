package com.tricrotism.uworldguard.event;

import com.tricrotism.uworldguard.flags.Flag;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Fired before a flag's value or its group is changed on a region. Cancelling leaves both as they
 * were.
 *
 * <p>A flag's effect is its value together with the group it applies to, so narrowing
 * {@code pvp: deny} from everyone to non-members is as much a change to what the region enforces as
 * clearing it. Both fire this event: compare {@link #getOldValue()} with {@link #getNewValue()} and
 * {@link #getOldGroup()} with {@link #getNewGroup()} to tell which moved.
 *
 * <p>A {@code null} {@link #getNewValue()} means the flag is being cleared, which also resets its
 * group to {@link RegionGroup#ALL}, and a {@code null} {@link #getOldValue()} that nothing had set
 * it. Both null cannot happen: clearing an unset flag is not an edit and fires nothing.
 *
 * <p>Values are the engine's own — a {@code String} for a string flag, a {@code State} for a state
 * flag, and so on. Cast against the flag, not against the name.
 *
 * @see RegionChangeEvent
 */
@NullMarked
public class RegionFlagChangeEvent extends RegionChangeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Flag<?> flag;
    private final @Nullable Object oldValue;
    private final @Nullable Object newValue;
    private final RegionGroup oldGroup;
    private final RegionGroup newGroup;

    public RegionFlagChangeEvent(
        final World world, final ProtectedRegion region, final Flag<?> flag,
        final @Nullable Object oldValue, final @Nullable Object newValue,
        final RegionGroup oldGroup, final RegionGroup newGroup,
        final @Nullable CommandSender actor
    ) {
        super(world, region, actor);
        this.flag = flag;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.oldGroup = oldGroup;
        this.newGroup = newGroup;
    }

    public Flag<?> getFlag() {
        return flag;
    }

    /**
     * What the region had, or {@code null} if the flag was unset.
     */
    public @Nullable Object getOldValue() {
        return oldValue;
    }

    /**
     * What it is about to have, or {@code null} if the flag is being cleared.
     */
    public @Nullable Object getNewValue() {
        return newValue;
    }

    /**
     * Who the value applied to on this region. {@link RegionGroup#ALL} when unqualified.
     */
    public RegionGroup getOldGroup() {
        return oldGroup;
    }

    /**
     * Who it is about to apply to. {@link RegionGroup#ALL} when unqualified or when clearing.
     */
    public RegionGroup getNewGroup() {
        return newGroup;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
