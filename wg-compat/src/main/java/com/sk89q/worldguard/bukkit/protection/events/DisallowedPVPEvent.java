// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.
package com.sk89q.worldguard.bukkit.protection.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when the {@code pvp} flag is about to deny one player's attack on another. Cancelling it
 * lets the attack through, which is how a combat plugin puts its own rules above the flag.
 *
 * <p>PvPManager registers a listener for this and refuses to enable its WorldGuard hook when the
 * class is missing.
 */
public class DisallowedPVPEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final Player defender;
    private final Event cause;
    private boolean cancelled;

    public DisallowedPVPEvent(final Player attacker, final Player defender, final Event cause) {
        this.attacker = attacker;
        this.defender = defender;
        this.cause = cause;
    }

    public Player getAttacker() {
        return attacker;
    }

    public Player getDefender() {
        return defender;
    }

    /**
     * The Bukkit event the attack arrived on.
     */
    public Event getCause() {
        return cause;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
