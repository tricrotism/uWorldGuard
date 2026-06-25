package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.StateFlag;
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
import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code permit-workbenches} blocks opening work-stations (crafting table, anvils, ender chest, …)
 * and 3×3 crafting-table crafting; {@code inventory-craft} blocks 2×2 inventory crafting.
 */
@NullMarked
public final class WorkbenchListener implements Listener {

    private static final String BYPASS = "uworldguard.bypass";

    private static final Set<Material> WORKBENCHES = EnumSet.of(
        Material.CRAFTING_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
        Material.ENDER_CHEST, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.LOOM,
        Material.CARTOGRAPHY_TABLE, Material.STONECUTTER, Material.ENCHANTING_TABLE);

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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || !WORKBENCHES.contains(block.getType())) {
            return;
        }
        final Player player = event.getPlayer();
        if (!query.getApplicableRegions(block).testState(Flags.PERMIT_WORKBENCHES)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
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
        if (!query.getApplicableRegions(player).testState(flag)) {
            if (player.hasPermission(BYPASS)) {
                return;
            }
            event.setCancelled(true);
            messages.send(player, "no-permission");
        }
    }
}
