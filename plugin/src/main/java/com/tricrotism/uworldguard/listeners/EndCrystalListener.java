package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Enforces {@code end-crystal-place} (placing an end crystal) and {@code end-crystal-interact}
 * (attacking / detonating one). End crystals are entities, so neither path is reached by the
 * block-place or interact flags.
 */
@NullMarked
public final class EndCrystalListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private final RegionQuery query;
    private final MessageService messages;

    public EndCrystalListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(final EntityPlaceEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!query.testState(crystal, Flags.END_CRYSTAL_PLACE)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        final Player player = playerSource(event.getDamager());
        if (player == null) {
            return;
        }
        if (!query.testState(crystal, Flags.END_CRYSTAL_INTERACT)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }

    private static @Nullable Player playerSource(final Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
