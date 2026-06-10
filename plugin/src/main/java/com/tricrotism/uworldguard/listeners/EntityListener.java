package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NullMarked;

/**
 * Enforces mob-spawning, explosion, enderman-grief, mob-damage, and damage-animals flags.
 */
@NullMarked
public final class EntityListener implements Listener {

    private final RegionQuery query;

    public EntityListener(final RegionQuery query) {
        this.query = query;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!isNaturalSpawn(event.getSpawnReason())) return;

        if (!query.testState(event.getLocation(), Flags.MOB_SPAWNING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(final EntityExplodeEvent event) {
        if (event.blockList().isEmpty()) {
            return;
        }
        final StateFlag flag = explosionFlag(event.getEntity());

        event.blockList().removeIf(block -> !query.testState(block.getLocation(), flag));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEndermanGrief(final EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Enderman
            && !query.testState(event.getBlock().getLocation(), Flags.ENDERMAN_GRIEF)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {
        final Entity victim = event.getEntity();
        final Entity damager = event.getDamager();

        if (victim instanceof Player && damager instanceof Mob
            && !query.testState(victim.getLocation(), Flags.MOB_DAMAGE)) {
            event.setCancelled(true);
            return;
        }

        if (victim instanceof Animals && isPlayerSource(damager)
            && !query.testState(victim.getLocation(), Flags.DAMAGE_ANIMALS)) {
            event.setCancelled(true);
        }
    }

    private static boolean isNaturalSpawn(final CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, SPAWNER, REINFORCEMENTS, PATROL, RAID, JOCKEY, MOUNT, VILLAGE_INVASION, TRAP -> true;
            default -> false;
        };
    }

    private static StateFlag explosionFlag(final Entity entity) {
        return switch (entity) {
            case Creeper _ -> Flags.CREEPER_EXPLOSION;
            case TNTPrimed _ -> Flags.TNT;
            case Fireball _ -> Flags.GHAST_FIREBALL;
            default -> Flags.OTHER_EXPLOSION;
        };
    }

    private static boolean isPlayerSource(final Entity damager) {
        if (damager instanceof Player) {
            return true;
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player;
        }
        return false;
    }
}
