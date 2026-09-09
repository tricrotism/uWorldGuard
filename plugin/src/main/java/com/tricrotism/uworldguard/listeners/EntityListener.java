package com.tricrotism.uworldguard.listeners;

import com.destroystokyo.paper.event.entity.EntityZapEvent;
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.sk89q.worldguard.bukkit.util.Events;
import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Enforces mob-spawning and deny-spawn, the explosion flags, mob grief (enderman, ravager, wither,
 * ender dragon), mob-damage, damage-animals, firework-damage, lightning, potion-splash,
 * item-frame/painting destruction, and mob-drops / exp-drops.
 */
@NullMarked
public final class EntityListener implements Listener {

    private final RegionContainerImpl container;
    private final RegionQuery query;

    public EntityListener(final RegionContainerImpl container, final RegionQuery query) {
        this.container = container;
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        final boolean natural = isNaturalSpawn(reason);
        final boolean copperGolem = reason == CreatureSpawnEvent.SpawnReason.BUILD_COPPERGOLEM;
        if (!natural && !copperGolem && !container.anyRegionUses(Flags.DENY_SPAWN)) {
            return;
        }

        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());

        if (set.worldUses(Flags.DENY_SPAWN)
            && set.flagSetContains(Flags.DENY_SPAWN, event.getEntityType())) {
            event.setCancelled(true);
            return;
        }

        if (copperGolem) {
            if (!set.testState(Flags.COPPER_GOLEM)) {
                event.setCancelled(true);
            }
            return;
        }

        if (natural && !set.testState(Flags.MOB_SPAWNING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(final EntityExplodeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.blockList().isEmpty()) {
            return;
        }
        final StateFlag flag = explosionFlag(event.getEntity());

        event.blockList().removeIf(block -> !query.testState(block, flag));
    }

    /**
     * Mobs that rearrange blocks by touching them rather than by exploding: endermen lifting blocks,
     * ravagers trampling leaves and crops, the wither and the dragon carving through terrain.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(final EntityChangeBlockEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final StateFlag flag = griefFlag(event.getEntity());
        if (flag != null && !query.testState(event.getBlock(), flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Lightning is what actually starts the fire and converts the mobs it hits, so denying the strike
     * itself is the only point that covers every consequence.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLightning(final LightningStrikeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getLightning(), Flags.LIGHTNING)) {
            event.setCancelled(true);
        }
    }

    /**
     * Splash and lingering potions. Cancelling outright would also destroy a beneficial potion thrown
     * by its owner, so instead every affected entity has its intensity zeroed — the bottle still
     * breaks and the particles still show, but nothing inside the region is affected.
     *
     * <p>The affected entities are copied because {@code setIntensity} writes back into the live
     * collection being walked, so the registry check comes first: with the flag unused nowhere on the
     * server, the handler costs a bitset test per world and copies nothing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(final PotionSplashEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!container.anyRegionUses(Flags.POTION_SPLASH)) {
            return;
        }

        for (final LivingEntity affected : List.copyOf(event.getAffectedEntities())) {
            if (!query.testState(affected, Flags.POTION_SPLASH)) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionSplash(final LingeringPotionSplashEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getAreaEffectCloud(), Flags.POTION_SPLASH)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Entity victim = event.getEntity();
        final Entity damager = event.getDamager();

        if (victim instanceof Player && damager instanceof Mob
            && !query.testState(victim, Flags.MOB_DAMAGE)) {
            event.setCancelled(true);
            return;
        }

        if (damager instanceof Firework && !query.testState(victim, Flags.FIREWORK_DAMAGE)) {
            event.setCancelled(true);
            return;
        }

        if (victim instanceof Animals) {
            final Player attacker = resolveAttacker(damager);
            if (attacker != null
                && !query.getApplicableRegions(victim)
                .testState(Flags.DAMAGE_ANIMALS, attacker.getUniqueId())
                && !Bypass.has(attacker)) {
                event.setCancelled(true);
            }
            return;
        }

        if (victim instanceof ItemFrame) {
            final Player attacker = resolveAttacker(damager);
            final UUID subject = attacker == null ? null : attacker.getUniqueId();
            if (!query.getApplicableRegions(victim).testState(Flags.ENTITY_ITEM_FRAME_DESTROY, subject)
                && (attacker == null || !Bypass.has(attacker))) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Mob conversions — zombie to drowned, villager to witch, a creaking waking up. The result is a
     * different mob than the region's owner allowed in, so it is worth its own gate.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getEntity(), Flags.ENTITY_TRANSFORM)) {
            event.setCancelled(true);
        }
    }

    /**
     * A lightning strike converting something — pig to zombified piglin, villager to witch, creeper to
     * charged. {@link EntityZapEvent} subclasses {@code EntityTransformEvent} but declares its own
     * handler list, so {@link #onTransform} never sees it; without this handler the most recognisable
     * transform in the game is the one {@code entity-transform} misses.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onZap(final EntityZapEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getEntity(), Flags.ENTITY_TRANSFORM)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(final EntityBreedEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final LivingEntity breeder = event.getBreeder();
        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());
        if (breeder instanceof Player player) {
            if (set.testState(Flags.BREED, player.getUniqueId()) || Bypass.has(player)) {
                return;
            }
        } else if (set.testState(Flags.BREED)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTame(final EntityTameEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());
        if (event.getOwner() instanceof Player player) {
            if (set.testState(Flags.TAME, player.getUniqueId()) || Bypass.has(player)) {
                return;
            }
        } else if (set.testState(Flags.TAME)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Zombies breaking down doors. This event extends {@code EntityChangeBlockEvent}, but
     * {@link #onMobGrief} resolves no flag for a zombie, so the two never both act on one break.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDoorBreak(final EntityBreakDoorEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getBlock(), Flags.DOOR_BREAK)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether a raid may start here — the one flag a spawn town usually wants.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRaidTrigger(final RaidTriggerEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!query.testState(event.getPlayer(), Flags.RAID)) {
            event.setCancelled(true);
        }
    }

    /**
     * Setting something alight. Burn damage arrives later as a plain {@code FIRE_TICK}
     * {@code EntityDamageEvent} with no attacker attached, so {@code pvp} and {@code mob-damage} —
     * which both key on the damager — never see it. Fire aspect and flame bows were therefore a way
     * to keep hurting players in a {@code pvp: deny} region; this stops the ignition instead.
     *
     * <p>Because this is the {@code pvp} flag by another route, a plugin that overrides the flag
     * through {@link DisallowedPVPEvent} overrides the ignition too. WorldGuard fires that event
     * from the damage path alone, having no combustion handler to fire it from.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombustByEntity(final EntityCombustByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Entity victim = event.getEntity();
        final Entity source = event.getCombuster();
        if (!(victim instanceof Player defender)) {
            return;
        }
        if (source instanceof Mob) {
            if (!query.testState(victim, Flags.MOB_DAMAGE)) {
                event.setCancelled(true);
            }
            return;
        }
        final Player attacker = resolveAttacker(source);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (!query.getApplicableRegions(victim).testState(Flags.PVP, attacker.getUniqueId())
            && !Bypass.has(attacker)
            && !Events.fireAndTestCancel(new DisallowedPVPEvent(attacker, defender, event))) {
            event.setCancelled(true);
        }
    }

    private static @Nullable Player resolveAttacker(final Entity source) {
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * Protects item frames and paintings from being broken by an entity — a player, a skeleton's
     * arrow, a creeper blast. Block-break protection does not cover these: they are entities, so
     * without this they stay destroyable inside an otherwise protected region.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Hanging hanging = event.getEntity();
        final StateFlag flag = hanging instanceof ItemFrame
            ? Flags.ENTITY_ITEM_FRAME_DESTROY
            : hanging instanceof Painting ? Flags.ENTITY_PAINTING_DESTROY : null;
        if (flag == null || query.testState(hanging, flag)) {
            return;
        }
        final Entity remover = event.getRemover();
        if (remover instanceof Player player && Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Enforces mob-drops / exp-drops. Players are excluded — their drops are governed by the
     * keep-inventory / keep-exp flags on {@code PlayerDeathEvent}, which is a subclass of this event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(final EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || EventGate.disabled(event)) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());
        if (set.worldUses(Flags.MOB_DROPS) && !set.testState(Flags.MOB_DROPS)) {
            event.getDrops().clear();
        }
        if (set.worldUses(Flags.EXP_DROPS) && !set.testState(Flags.EXP_DROPS)) {
            event.setDroppedExp(0);
        }
    }

    private static boolean isNaturalSpawn(final CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, SPAWNER, TRIAL_SPAWNER, REINFORCEMENTS, PATROL, RAID, JOCKEY, MOUNT,
                 VILLAGE_INVASION, TRAP -> true;
            default -> false;
        };
    }

    private static StateFlag explosionFlag(final Entity entity) {
        return switch (entity) {
            case Creeper _ -> Flags.CREEPER_EXPLOSION;
            case TNTPrimed _ -> Flags.TNT;
            case WitherSkull _, Wither _ -> Flags.WITHER_DAMAGE;
            case BreezeWindCharge _ -> Flags.BREEZE_CHARGE_EXPLOSION;
            case WindCharge _ -> Flags.OTHER_EXPLOSION;
            case Fireball _ -> Flags.GHAST_FIREBALL;
            case EnderDragon _ -> Flags.ENDERDRAGON_BLOCK_DAMAGE;
            default -> Flags.OTHER_EXPLOSION;
        };
    }

    private static @Nullable StateFlag griefFlag(final Entity entity) {
        return switch (entity) {
            case Enderman _ -> Flags.ENDERMAN_GRIEF;
            case Ravager _ -> Flags.RAVAGER_GRIEF;
            case Wither _ -> Flags.WITHER_DAMAGE;
            case EnderDragon _ -> Flags.ENDERDRAGON_BLOCK_DAMAGE;
            default -> null;
        };
    }

}
