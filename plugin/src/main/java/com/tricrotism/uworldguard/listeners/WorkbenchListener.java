package com.tricrotism.uworldguard.listeners;

import com.tricrotism.uworldguard.config.Bypass;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.flags.StateFlag;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
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
 * and 3×3 crafting-table crafting. {@code inventory-craft} blocks 2×2 inventory crafting.
 *
 * <p>Opening only refuses on an explicit deny. Membership is already enforced by the
 * {@code interact}/{@code use} check in {@link BuildProtectionListener}, which runs first, so an
 * unset flag defers to it and {@code use: allow} opens a smithing table for everyone.
 */
@NullMarked
public final class WorkbenchListener implements Listener {

    private static final Set<Material> ANVILS = EnumSet.of(
        Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL);

    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final MessageService messages;

    public WorkbenchListener(final RegionContainerImpl container, final RegionQuery query, final MessageService messages) {
        this.container = container;
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
        if (!container.anyRegionUses(Flags.PERMIT_WORKBENCHES) && !container.anyRegionUses(Flags.USE_ANVIL)) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final Material type = block.getType();
        if (!InteractFlags.WORKBENCHES.contains(type)) {
            return;
        }
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final ApplicableRegionSet set = query.getApplicableRegions(block);
        final StateFlag denied;
        final State anvil = ANVILS.contains(type)
            ? set.queryExplicitState(Flags.USE_ANVIL, uuid) : null;
        if (anvil == State.ALLOW) {
            return;
        }
        if (anvil == State.DENY) {
            denied = Flags.USE_ANVIL;
        } else if (set.queryExplicitState(Flags.PERMIT_WORKBENCHES, uuid) == State.DENY) {
            denied = Flags.PERMIT_WORKBENCHES;
        } else {
            return;
        }
        if (Bypass.has(player) || set.flagSetContains(Flags.ALLOW_INTERACT, type, uuid)) {
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
        final StateFlag flag = event.getInventory().getSize() > 5
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
