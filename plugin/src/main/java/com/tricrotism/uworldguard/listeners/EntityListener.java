package com.tricrotism.uworldguard.listeners;

import com.destroystokyo.paper.event.entity.EntityZapEvent;
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.DenyDebug;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * Enforces mob-spawning and deny-spawn, the explosion flags, mob grief (enderman, ravager, wither,
 * ender dragon), mob-damage, damage-animals, allow-entity-damage, firework-damage, lightning, potion-splash,
 * item-frame/painting/armor-stand destruction, and mob-drops / exp-drops. Also pvp for thrown and
 * lingering potions, and block-break for blocks a projectile shatters.
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
        final World world = event.getEntity().getWorld();
        final boolean denySpawn = query.usesFlag(world, Flags.DENY_SPAWN);
        if (!denySpawn
            && !(natural && query.usesFlag(world, Flags.MOB_SPAWNING))
            && !(copperGolem && query.usesFlag(world, Flags.COPPER_GOLEM))) {
            return;
        }

        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());

        if (denySpawn && set.flagSetContains(Flags.DENY_SPAWN, event.getEntityType())) {
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

    /**
     * Blocks a blast may break: its explosion flag, then build rights as in WorldGuard. Whoever lit
     * the TNT breaks only where they could build. A blast nobody is behind breaks only in the claim it
     * started in, so a creeper or a cannon outside a claim cannot dig into it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(final EntityExplodeEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.blockList().isEmpty()) {
            return;
        }
        final Entity entity = event.getEntity();
        final StateFlag flag = explosionFlag(entity);
        final Player breaker = Attackers.of(entity);
        if (breaker != null && Bypass.has(breaker)) {
            if (query.usesFlag(entity.getWorld(), flag)) {
                query.removeDenied(entity.getWorld(), event.blockList(), flag);
            }
            return;
        }
        query.removeUnbreakable(entity.getWorld(), event.blockList(), flag, flag == Flags.TNT,
            breaker == null ? null : breaker.getUniqueId(), breaker == null ? Attackers.sourceRegions(query, entity) : null);
    }

    /**
     * Mobs that rearrange blocks by touching them rather than by exploding: endermen lifting blocks,
     * ravagers trampling leaves and crops, the wither and the dragon carving through terrain.
     *
     * <p>Projectiles land here too: an arrow or trident shattering a decorated pot, pointed dripstone
     * or a chorus flower. A player's shot is a block break by that player, membership included. Any
     * other shooter only meets an explicit {@code block-break} deny.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(final EntityChangeBlockEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final Entity entity = event.getEntity();
        if (entity instanceof Projectile projectile) {
            onProjectileChange(event, projectile);
            return;
        }
        if (entity instanceof Player) {
            return;
        }
        final Block block = event.getBlock();
        final StateFlag flag = griefFlag(entity);
        final ApplicableRegionSet at = query.getApplicableRegions(block);
        if (flag != null && query.usesFlag(block.getWorld(), flag) && !at.testState(flag)) {
            event.setCancelled(true);
            return;
        }
        if (at.size() != 0 && !MachineListener.machineMayAct(
            query.getApplicableRegions(entity), at, flag != null ? flag : Flags.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    private void onProjectileChange(final EntityChangeBlockEvent event, final Projectile projectile) {
        final Block block = event.getBlock();
        if (projectile.getShooter() instanceof Player shooter) {
            if (!query.getApplicableRegions(block).testBuild(shooter.getUniqueId(), Flags.BLOCK_BREAK)
                && !Bypass.has(shooter)) {
                event.setCancelled(true);
            }
            return;
        }
        if (query.usesFlag(block.getWorld(), Flags.BLOCK_BREAK) && !query.testState(block, Flags.BLOCK_BREAK)) {
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
        if (query.usesFlag(event.getWorld(), Flags.LIGHTNING)
            && !query.testState(event.getLightning(), Flags.LIGHTNING)) {
            event.setCancelled(true);
        }
    }

    /**
     * Splash and lingering potions. Cancelling outright would also destroy a beneficial potion thrown
     * by its owner, so instead every affected entity has its intensity zeroed. The bottle still
     * breaks and the particles still show, but nothing inside the region is affected.
     *
     * <p>{@code getAffectedEntities} already returns a fresh copy, so {@code setIntensity} can change
     * the event while the loop walks it. The registry checks come first: with the flags unused on the
     * server, the handler costs a bitset test per world and builds no copy.
     *
     * <p>A harmful potion thrown by a player is also {@code pvp}: poison or slowness reaching another
     * player where pvp is denied to the thrower has its intensity zeroed for that player. Beneficial
     * potions still reach allies. A witch's potion is {@code mob-damage} for every player it reaches.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(final PotionSplashEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final boolean splashFlag = container.anyRegionUses(Flags.POTION_SPLASH);
        final ThrownPotion potion = event.getPotion();
        final Player thrower = container.anyRegionUses(Flags.PVP)
            && potion.getShooter() instanceof Player player && harmful(potion.getEffects()) ? player : null;
        final boolean mobThrown = container.anyRegionUses(Flags.MOB_DAMAGE)
            && potion.getShooter() instanceof LivingEntity shooter && !(shooter instanceof Player);
        if (!splashFlag && thrower == null && !mobThrown) {
            return;
        }

        for (final LivingEntity affected : event.getAffectedEntities()) {
            if (splashFlag && !query.testState(affected, Flags.POTION_SPLASH)) {
                event.setIntensity(affected, 0.0);
            } else if (thrower != null && affected instanceof Player defender
                && pvpDenied(thrower, defender, event)) {
                event.setIntensity(affected, 0.0);
            } else if (mobThrown && affected instanceof Player
                && !query.testState(affected, Flags.MOB_DAMAGE)) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    /**
     * Lingering clouds re-apply every few ticks, long after the splash event, so {@code pvp} is
     * checked again here. Victims are dropped from the affected list, which also covers instant
     * damage since the cloud only applies effects to what is left in it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloudApply(final AreaEffectCloudApplyEvent event) {
        if (EventGate.disabled(event) || !container.anyRegionUses(Flags.PVP)) {
            return;
        }
        final AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player attacker) || !harmful(cloud)) {
            return;
        }
        event.getAffectedEntities().removeIf(affected ->
            affected instanceof Player defender && pvpDenied(attacker, defender, event));
    }

    /**
     * The {@code pvp} test shared by every indirect attack. Hitting yourself is never pvp. Either
     * side's region can deny, as for a direct hit.
     */
    private boolean pvpDenied(final Player attacker, final Player defender, final Event cause) {
        final boolean denied = !attacker.equals(defender)
            && (!query.getApplicableRegions(defender).testState(Flags.PVP, attacker.getUniqueId())
            || !query.getApplicableRegions(attacker).testState(Flags.PVP, attacker.getUniqueId()))
            && !Bypass.has(attacker)
            && !Attackers.pvpRefusalOverridden(attacker, defender, cause);
        if (denied && DenyDebug.watching(attacker)) {
            final ApplicableRegionSet at = query.getApplicableRegions(defender);
            DenyDebug.explain(attacker, Flags.PVP, at.testState(Flags.PVP, attacker.getUniqueId())
                ? query.getApplicableRegions(attacker) : at);
        }
        return denied;
    }

    private void explainProtected(final Entity victim, final StateFlag flag) {
        if (victim instanceof Player player && DenyDebug.watching(player)) {
            DenyDebug.explainProtected(player, flag, query.getApplicableRegions(victim));
        }
    }

    private static boolean harmful(final AreaEffectCloud cloud) {
        final PotionType base = cloud.getBasePotionType();
        return (base != null && harmful(base.getPotionEffects()))
            || (cloud.hasCustomEffects() && harmful(cloud.getCustomEffects()));
    }

    private static boolean harmful(final Collection<PotionEffect> effects) {
        for (final PotionEffect effect : effects) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionSplash(final LingeringPotionSplashEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        final AreaEffectCloud cloud = event.getAreaEffectCloud();
        if (query.usesFlag(cloud.getWorld(), Flags.POTION_SPLASH)
            && !query.testState(cloud, Flags.POTION_SPLASH)) {
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

        // lingering harming damage names the cloud, not a projectile, so the pvp listener misses it
        if (damager instanceof AreaEffectCloud cloud && victim instanceof Player defender
            && cloud.getSource() instanceof Player attacker && container.anyRegionUses(Flags.PVP)
            && pvpDenied(attacker, defender, event)) {
            event.setCancelled(true);
            return;
        }

        final StateFlag blast = switch (damager) {
            case EnderCrystal _ -> Flags.OTHER_EXPLOSION;
            case Creeper _ -> Flags.CREEPER_EXPLOSION;
            case LightningStrike _ -> Flags.LIGHTNING;
            default -> null;
        };
        ApplicableRegionSet at = null;
        if (blast != null && query.usesFlag(victim.getWorld(), blast)) {
            at = query.getApplicableRegions(victim);
            if (!at.testState(blast)) {
                event.setCancelled(true);
                explainProtected(victim, blast);
                return;
            }
        }

        if (victim instanceof Player player) {
            final boolean shotByMob = damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter && !(shooter instanceof Player);
            final boolean mob = shotByMob
                || damager instanceof Mob && !(damager instanceof Tameable pet && pet.isTamed());
            if (mob && query.usesFlag(victim.getWorld(), Flags.MOB_DAMAGE)) {
                if (at == null) {
                    at = query.getApplicableRegions(victim);
                }
                if (!at.testState(Flags.MOB_DAMAGE, player.getUniqueId())) {
                    event.setCancelled(true);
                    explainProtected(victim, Flags.MOB_DAMAGE);
                    return;
                }
            }
            if (shotByMob && damager instanceof Fireball) {
                final StateFlag flag = explosionFlag(damager);
                if (query.usesFlag(victim.getWorld(), flag)) {
                    if (at == null) {
                        at = query.getApplicableRegions(victim);
                    }
                    if (!at.testState(flag)) {
                        event.setCancelled(true);
                        explainProtected(victim, flag);
                        return;
                    }
                }
            }
        }

        if (damager instanceof Firework && query.usesFlag(victim.getWorld(), Flags.FIREWORK_DAMAGE)) {
            if (at == null) {
                at = query.getApplicableRegions(victim);
            }
            if (!at.testState(Flags.FIREWORK_DAMAGE, victim instanceof Player player ? player.getUniqueId() : null)) {
                event.setCancelled(true);
                return;
            }
        }

        final StateFlag flag = harmFlag(victim);
        if (flag == null) {
            return;
        }
        if (at == null) {
            at = query.getApplicableRegions(victim);
        }
        if (!at.flagSetContains(Flags.ALLOW_ENTITY_DAMAGE, victim.getType()) && !mayHarm(damager, at, flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * The flag that guards {@code victim} against harm from someone else, or {@code null} for a
     * player (pvp decides) or a hostile mob (anyone may fight it). {@link Flags#BUILD} stands for
     * build rights alone, for villagers, golems and the like.
     */
    private static @Nullable StateFlag harmFlag(final Entity victim) {
        return switch (victim) {
            case Animals _ -> Flags.DAMAGE_ANIMALS;
            case ItemFrame _ -> Flags.ENTITY_ITEM_FRAME_DESTROY;
            case ArmorStand _ -> Flags.ENTITY_ARMOR_STAND_DESTROY;
            case Vehicle _ -> Flags.VEHICLE_DESTROY;
            case Player _, Enemy _ -> null;
            case LivingEntity _ -> Flags.BUILD;
            default -> null;
        };
    }

    /**
     * Whatever {@code damager} traces back to may harm something guarded by {@code flag}: a player by
     * their build rights or bypass, anything else by where it came from.
     */
    private boolean mayHarm(final @Nullable Entity damager, final ApplicableRegionSet at, final StateFlag flag) {
        final Player attacker = Attackers.of(damager);
        if (attacker == null) {
            return Attackers.sourceMayBuild(query, damager, at, flag);
        }
        final boolean allowed = flag == Flags.BUILD
            ? at.canBuild(attacker.getUniqueId())
            : at.testBuild(attacker.getUniqueId(), flag);
        return allowed || Bypass.has(attacker);
    }

    /**
     * Damage with no entity behind it: a bed or respawn anchor detonating in the wrong dimension, or
     * a plugin explosion with no source, judged by {@code other-explosion} where the victim stands.
     * Wither ticks keep landing after the player walks away from the skull or rose that caused them,
     * so they are {@code mob-damage} there too.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSourcelessDamage(final EntityDamageEvent event) {
        final EntityDamageEvent.DamageCause cause = event.getCause();
        final StateFlag flag = cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            ? Flags.OTHER_EXPLOSION
            : cause == EntityDamageEvent.DamageCause.WITHER && event.getEntity() instanceof Player
            ? Flags.MOB_DAMAGE : null;
        if (flag == null || event instanceof EntityDamageByEntityEvent || EventGate.disabled(event)) {
            return;
        }
        final Entity victim = event.getEntity();
        if (!query.usesFlag(victim.getWorld(), flag)) {
            return;
        }
        final boolean allowed = flag == Flags.MOB_DAMAGE
            ? query.testState(victim, flag)
            : query.getApplicableRegions(victim).testState(flag);
        if (!allowed) {
            event.setCancelled(true);
            explainProtected(victim, flag);
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
        final Entity entity = event.getEntity();
        if (query.usesFlag(entity.getWorld(), Flags.ENTITY_TRANSFORM)
            && !query.testState(entity, Flags.ENTITY_TRANSFORM)) {
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
        final Entity entity = event.getEntity();
        ApplicableRegionSet at = null;
        if (query.usesFlag(entity.getWorld(), Flags.ENTITY_TRANSFORM)) {
            at = query.getApplicableRegions(entity);
            if (!at.testState(Flags.ENTITY_TRANSFORM, entity instanceof Player player ? player.getUniqueId() : null)) {
                event.setCancelled(true);
                return;
            }
        }
        final StateFlag flag = harmFlag(entity);
        if (flag != null && !mayHarm(event.getBolt(), at != null ? at : query.getApplicableRegions(entity), flag)) {
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
        final Block block = event.getBlock();
        if (query.usesFlag(block.getWorld(), Flags.DOOR_BREAK) && !query.testState(block, Flags.DOOR_BREAK)) {
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
        final Player player = event.getPlayer();
        if (query.usesFlag(player.getWorld(), Flags.RAID) && !query.testState(player, Flags.RAID)) {
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
            final StateFlag flag = harmFlag(victim);
            if (flag != null && !mayHarm(source, query.getApplicableRegions(victim), flag)) {
                event.setCancelled(true);
            }
            return;
        }
        if (source instanceof Mob) {
            if (!query.testState(victim, Flags.MOB_DAMAGE)) {
                event.setCancelled(true);
            }
            return;
        }
        final Player attacker = Attackers.of(source);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (pvpDenied(attacker, defender, event)) {
            event.setCancelled(true);
        }
    }

    /**
     * Knockback that lands without a hit, mostly a wind charge burst. Paper names the thrower as the
     * pusher, so a charge fired from outside cannot launch a player standing in a {@code pvp} deny region.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvpKnockback(final EntityPushedByEntityAttackEvent event) {
        if (!(event.getEntity() instanceof Player defender) || !container.anyRegionUses(Flags.PVP)
            || EventGate.disabled(event)) {
            return;
        }
        final Player attacker = Attackers.of(event.getPushedBy());
        if (attacker != null && pvpDenied(attacker, defender, event)) {
            event.setCancelled(true);
        }
    }

    /**
     * Reeling in a hooked entity drags it, so a rod cast from outside could pull a player out of a
     * {@code pvp} deny region, or an animal, stand or cart out of a claim.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFishPlayer(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || EventGate.disabled(event)) {
            return;
        }
        final Player player = event.getPlayer();
        final Entity caught = event.getCaught();
        if (caught instanceof Player defender) {
            if (container.anyRegionUses(Flags.PVP) && pvpDenied(player, defender, event)) {
                event.setCancelled(true);
            }
            return;
        }
        final StateFlag flag = caught == null ? null : harmFlag(caught);
        if (flag != null && !mayHarm(player, query.getApplicableRegions(caught), flag)) {
            event.setCancelled(true);
        }
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
            : hanging instanceof Painting ? Flags.ENTITY_PAINTING_DESTROY
            : hanging instanceof LeashHitch ? Flags.BUILD : null;
        if (flag == null) {
            return;
        }
        if (!mayHarm(event.getRemover(), query.getApplicableRegions(hanging), flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * A frame or painting knocked down by an explosion with no entity behind it, mostly a bed or
     * respawn anchor. Paper fires the plain event for that, which {@link #onHangingBreak} never
     * sees. Nobody is behind the blast, so it is a non-member everywhere, as in WorldGuard.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingExplode(final HangingBreakEvent event) {
        if (event.getCause() != HangingBreakEvent.RemoveCause.EXPLOSION
            || event instanceof HangingBreakByEntityEvent || EventGate.disabled(event)) {
            return;
        }
        final Hanging hanging = event.getEntity();
        final ApplicableRegionSet at = query.getApplicableRegions(hanging);
        final boolean allowed = switch (hanging) {
            case ItemFrame _ -> at.testBuild(null, Flags.OTHER_EXPLOSION, Flags.ENTITY_ITEM_FRAME_DESTROY);
            case Painting _ -> at.testBuild(null, Flags.OTHER_EXPLOSION, Flags.ENTITY_PAINTING_DESTROY);
            default -> at.testBuild(null, Flags.OTHER_EXPLOSION);
        };
        if (!allowed) {
            event.setCancelled(true);
        }
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
        final World world = event.getEntity().getWorld();
        final boolean mobDrops = query.usesFlag(world, Flags.MOB_DROPS);
        final boolean expDrops = query.usesFlag(world, Flags.EXP_DROPS);
        if (!mobDrops && !expDrops) {
            return;
        }
        final ApplicableRegionSet set = query.getApplicableRegions(event.getEntity());
        if (mobDrops && !set.testState(Flags.MOB_DROPS)) {
            event.getDrops().clear();
        }
        if (expDrops && !set.testState(Flags.EXP_DROPS)) {
            event.setDroppedExp(0);
        }
    }

    /**
     * Spawns {@code mob-spawning} governs: mobs the world brings in, not ones a player places or
     * breeds. A pearl's endermite counts, since nobody chose to spawn it.
     */
    private static boolean isNaturalSpawn(final CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, SPAWNER, TRIAL_SPAWNER, REINFORCEMENTS, PATROL, RAID, JOCKEY, MOUNT,
                 VILLAGE_INVASION, TRAP, ENDER_PEARL -> true;
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
