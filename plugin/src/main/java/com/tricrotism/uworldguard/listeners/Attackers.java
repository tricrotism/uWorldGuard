package com.tricrotism.uworldguard.listeners;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.sk89q.worldguard.bukkit.util.Events;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.projectiles.BlockProjectileSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Who is behind a hit, traced the way WorldGuard's {@code Cause} traces it, so every damage and
 * destroy check agrees on the attacker.
 */
@NullMarked final class Attackers {

    private Attackers() {}

    /**
     * Whether a plugin cancelled WorldGuard's {@code DisallowedPVPEvent} for this refusal, letting the
     * hit through. The event is built and fired only while something listens, since most servers hook
     * no combat plugin in.
     */
    static boolean pvpRefusalOverridden(final Player attacker, final Player defender, final Event cause) {
        return DisallowedPVPEvent.getHandlerList().getRegisteredListeners().length != 0
            && Events.fireAndTestCancel(new DisallowedPVPEvent(attacker, defender, cause));
    }

    /**
     * The player responsible for {@code damager}: the damager itself, a projectile's shooter, whoever
     * lit the TNT, whoever threw the channeling trident, or a tamed animal's online owner. An end
     * crystal is deliberately not traced, as in WorldGuard, so crystal damage is never pvp.
     */
    static @Nullable Player of(final @Nullable Entity damager) {
        return switch (damager) {
            case Player player -> player;
            case Projectile projectile when projectile.getShooter() instanceof Player player -> player;
            case AreaEffectCloud cloud when cloud.getSource() instanceof Player player -> player;
            case TNTPrimed tnt when tnt.getSource() instanceof Player player -> player;
            case LightningStrike bolt when bolt.getCausingEntity() instanceof Player player -> player;
            case Tameable pet when pet.isTamed() -> owner(pet);
            case null, default -> null;
        };
    }

    private static @Nullable Player owner(final Tameable pet) {
        final UUID id = pet.getOwnerUniqueId();
        return id == null ? null : Bukkit.getPlayer(id);
    }

    /**
     * WorldGuard's build test for a source with no player behind it. A source standing in one of the
     * target's regions (or a parent of one) acts as a member there, so only an explicit deny stops it.
     * Anything from outside, and anything that cannot be placed (a tamed animal whose owner is offline,
     * an explosion with no entity), is a non-member.
     */
    static boolean sourceMayBuild(
        final RegionQuery query, final @Nullable Entity damager, final ApplicableRegionSet at, final StateFlag flag
    ) {
        final State explicit = at.queryExplicitState(flag, null);
        if (explicit == State.DENY) {
            return false;
        }
        if (explicit == State.ALLOW || at.canBuild(null)) {
            return true;
        }
        final ApplicableRegionSet source = sourceRegions(query, damager);
        return source != null && at.overlaps(source);
    }

    /**
     * The regions a cause with no player behind it came from: a projectile's shooter, and TNT or a
     * TNT cart where it was primed, so a cannon fired from outside a claim stays outside it. A mob,
     * ridden or not, counts from where it stands. {@code null} when it cannot be placed.
     */
    static @Nullable ApplicableRegionSet sourceRegions(final RegionQuery query, final @Nullable Entity damager) {
        return switch (damager) {
            case null -> null;
            case Projectile projectile -> switch (projectile.getShooter()) {
                case Entity shooter -> query.getApplicableRegions(shooter);
                case BlockProjectileSource block -> query.getApplicableRegions(block.getBlock());
                case null, default -> query.getApplicableRegions(projectile);
            };
            case TNTPrimed _ -> primedAt(query, damager);
            case Vehicle _ when !(damager instanceof LivingEntity) -> primedAt(query, damager);
            case Tameable pet when pet.isTamed() -> null;
            default -> query.getApplicableRegions(damager);
        };
    }

    private static @Nullable ApplicableRegionSet primedAt(final RegionQuery query, final Entity entity) {
        final Location origin = entity.getOrigin();
        return origin == null || origin.getWorld() == null ? null : query.getApplicableRegions(origin);
    }
}
