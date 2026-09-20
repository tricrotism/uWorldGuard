package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.text.MessageService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code permit-workbenches} blocks opening work-stations (crafting table, anvils, ender chest, …)
 * and 3×3 crafting-table crafting; {@code inventory-craft} blocks 2×2 inventory crafting.
 */
@NullMarked
public final class WorkbenchListener implements Listener {

    private static final Set<Material> ANVILS = EnumSet.of(
        Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL);

    private final RegionQuery query;
    private final MessageService messages;

    public WorkbenchListener(final RegionQuery query, final MessageService messages) {
        this.query = query;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(final PlayerInteractEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || !InteractFlags.WORKBENCHES.contains(block.getType())) {
            return;
        }
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final StateFlag denied;
        final State anvil = ANVILS.contains(block.getType())
            ? set.queryExplicitState(Flags.USE_ANVIL, uuid) : null;
        if (anvil == State.ALLOW) {
            return;
        }
        if (anvil == State.DENY) {
            denied = Flags.USE_ANVIL;
        } else if (!set.testBuild(uuid, Flags.PERMIT_WORKBENCHES)) {
            denied = Flags.PERMIT_WORKBENCHES;
        } else {
            return;
        }
        if (Bypass.has(player)) {
            return;
        }
        event.setCancelled(true);
        messages.sendDeny(player, denied);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(final CraftItemEvent event) {
        if (EventGate.disabled(event)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final StateFlag flag = event.getInventory().getMatrix().length > 4
            ? Flags.PERMIT_WORKBENCHES : Flags.INVENTORY_CRAFT;
        if (!query.getApplicableRegions(player).testState(flag, player.getUniqueId())) {
            if (Bypass.has(player)) {
                return;
            }
            event.setCancelled(true);
            messages.sendDeny(player, flag);
        }
    }
}
