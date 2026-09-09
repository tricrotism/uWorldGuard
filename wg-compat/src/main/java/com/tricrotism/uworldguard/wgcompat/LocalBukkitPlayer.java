// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.tricrotism.uworldguard.wgcompat;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import com.sk89q.worldguard.LocalPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.UUID;

/**
 * The {@code LocalPlayer} handed to WorldGuard consumers for an online player.
 *
 * <p>Extends WorldEdit's own {@code BukkitPlayer} rather than proxying the WorldEdit surface,
 * because that inheritance is load-bearing for consumers. {@code BukkitAdapter.adapt(Player)} is
 * how a plugin gets back from a WorldGuard handler to a Bukkit player, and it is a cast to this
 * exact class. WorldGuard's own {@code BukkitPlayer} extends it for the same reason. A dynamic proxy
 * satisfies the interfaces and fails that cast, which surfaces as a {@code ClassCastException}
 * inside the consumer, not here.
 *
 * <p>Only the members WorldGuard adds on top of WorldEdit are implemented; everything else is
 * WorldEdit's, so it moves with whatever WorldEdit is installed. Offline players have no
 * WorldEdit player to extend and keep the proxy in {@link PlayerWrapping}.
 */
final class LocalBukkitPlayer extends com.sk89q.worldedit.bukkit.BukkitPlayer
    implements LocalPlayer, UuidSubject {

    private final Player bukkit;
    private final UUID uniqueId;

    LocalBukkitPlayer(final Player bukkit) {
        super(bukkit);
        this.bukkit = bukkit;
        this.uniqueId = bukkit.getUniqueId();
    }

    Player bukkit() {
        return bukkit;
    }

    @Override
    public UUID uwgUuid() {
        return uniqueId;
    }

    @Override
    public boolean hasGroup(final String group) {
        return Groups.inGroup(uniqueId, group);
    }

    @Override
    public double getHealth() {
        return bukkit.getHealth();
    }

    @Override
    public void setHealth(final double health) {
        bukkit.setHealth(health);
    }

    @Override
    public double getMaxHealth() {
        final AttributeInstance attribute = bukkit.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getValue();
    }

    @Override
    public double getFoodLevel() {
        return bukkit.getFoodLevel();
    }

    @Override
    public void setFoodLevel(final double foodLevel) {
        bukkit.setFoodLevel((int) foodLevel);
    }

    @Override
    public double getSaturation() {
        return bukkit.getSaturation();
    }

    @Override
    public void setSaturation(final double saturation) {
        bukkit.setSaturation((float) saturation);
    }

    @Override
    public float getExhaustion() {
        return bukkit.getExhaustion();
    }

    @Override
    public void setExhaustion(final float exhaustion) {
        bukkit.setExhaustion(exhaustion);
    }

    @Override
    public int getFireTicks() {
        return bukkit.getFireTicks();
    }

    @Override
    public void setFireTicks(final int fireTicks) {
        bukkit.setFireTicks(fireTicks);
    }

    @Override
    public void resetFallDistance() {
        bukkit.setFallDistance(0.0F);
    }

    @Override
    public void setCompassTarget(final Location location) {
        bukkit.setCompassTarget(toBukkit(location));
    }

    @Override
    public long getPlayerTimeOffset() {
        return bukkit.getPlayerTimeOffset();
    }

    @Override
    public boolean isPlayerTimeRelative() {
        return bukkit.isPlayerTimeRelative();
    }

    @Override
    public void setPlayerTime(final long time, final boolean relative) {
        bukkit.setPlayerTime(time, relative);
    }

    @Override
    public void resetPlayerTime() {
        bukkit.resetPlayerTime();
    }

    @Override
    public WeatherType getPlayerWeather() {
        final org.bukkit.WeatherType player = bukkit.getPlayerWeather();
        final boolean clear = player != null
            ? player == org.bukkit.WeatherType.CLEAR
            : !bukkit.getWorld().hasStorm();
        return clear ? WeatherTypes.CLEAR : WeatherTypes.RAIN;
    }

    @Override
    public void setPlayerWeather(final WeatherType weather) {
        final boolean clear = weather == null || weather.id().equals(WeatherTypes.CLEAR.id());
        bukkit.setPlayerWeather(clear ? org.bukkit.WeatherType.CLEAR : org.bukkit.WeatherType.DOWNFALL);
    }

    @Override
    public void resetPlayerWeather() {
        bukkit.resetPlayerWeather();
    }

    @Override
    public void kick(final String msg) {
        bukkit.kick(Component.text(String.valueOf(msg)));
    }

    @Override
    public void ban(final String msg) {
        bukkit.ban(msg, (Date) null, null);
    }

    @Override
    public void sendTitle(final String title, final String subtitle) {
        bukkit.showTitle(Title.title(
            Component.text(title == null ? "" : title),
            Component.text(subtitle == null ? "" : subtitle)));
    }

    @Override
    public void teleport(final Location location, final String successMessage, final String failMessage) {
        bukkit.teleportAsync(toBukkit(location)).thenAccept(moved -> {
            final String message = moved ? successMessage : failMessage;
            if (message != null && !message.isEmpty()) {
                bukkit.sendMessage(Component.text(message));
            }
        });
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof UuidSubject subject && uniqueId.equals(subject.uwgUuid());
    }

    @Override
    public int hashCode() {
        return uniqueId.hashCode();
    }

    @Override
    public String toString() {
        return "LocalPlayer{" + uniqueId + '}';
    }

    private org.bukkit.Location toBukkit(final Location location) {
        return new org.bukkit.Location(bukkit.getWorld(), location.getX(), location.getY(),
            location.getZ(), location.getYaw(), location.getPitch());
    }
}
