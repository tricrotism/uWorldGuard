// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.List;
import java.util.UUID;

/**
 * A player as WorldGuard sees one: everything WorldEdit's {@code Player} offers, plus the handful of
 * Bukkit-side accessors WorldGuard's own handlers need, plus region association.
 *
 * <p>Instances come from {@code com.tricrotism.uworldguard.wgcompat.PlayerWrapping}. An online
 * player gets a class extending WorldEdit's {@code BukkitPlayer}, with the methods below answered
 * against Bukkit. That is the same base WorldGuard's own implementation uses, and what
 * {@code BukkitAdapter.adapt(Player)} casts to. An offline player, having no WorldEdit player, gets a
 * {@link java.lang.reflect.Proxy} that answers identity and association and refuses the rest. Both
 * implement {@code com.tricrotism.uworldguard.wgcompat.UuidSubject}, so region queries made with
 * them take the engine's UUID fast path rather than the per-region associable walk.
 */
public interface LocalPlayer extends com.sk89q.worldedit.entity.Player, RegionAssociable {

    boolean hasGroup(String group);

    double getHealth();

    void setHealth(double health);

    double getMaxHealth();

    double getFoodLevel();

    void setFoodLevel(double foodLevel);

    double getSaturation();

    void setSaturation(double saturation);

    float getExhaustion();

    void setExhaustion(float exhaustion);

    int getFireTicks();

    void setFireTicks(int fireTicks);

    void resetFallDistance();

    void setCompassTarget(Location location);

    long getPlayerTimeOffset();

    boolean isPlayerTimeRelative();

    void setPlayerTime(long time, boolean relative);

    void resetPlayerTime();

    WeatherType getPlayerWeather();

    void setPlayerWeather(WeatherType weather);

    void resetPlayerWeather();

    void kick(String msg);

    void ban(String msg);

    void sendTitle(String title, String subtitle);

    void teleport(Location location, String successMessage, String failMessage);

    /**
     * Owner of any region wins, then member of any region, otherwise non-member — WorldGuard's
     * resolution for a plain player subject.
     */
    @Override
    default Association getAssociation(final List<ProtectedRegion> regions) {
        final UUID uniqueId = getUniqueId();
        boolean member = false;
        for (int i = 0, n = regions.size(); i < n; i++) {
            final ProtectedRegion region = regions.get(i);
            if (region.isOwner(uniqueId)) {
                return Association.OWNER;
            }
            if (!member && region.isMember(uniqueId)) {
                member = true;
            }
        }
        return member ? Association.MEMBER : Association.NON_MEMBER;
    }
}
