package com.tricrotism.uworldguard.flags;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * A flag whose value is a set of potion effects — used by {@code give-effects} (the amplifier is
 * applied) and {@code blocked-effects} (only the effect type is consulted). Parsing accepts a
 * comma-separated list of {@code TYPE} or {@code TYPE:amplifier} tokens, where {@code TYPE} is a
 * potion-effect name such as {@code SPEED}, {@code NIGHT_VISION}, or {@code JUMP}.
 *
 * <p>The stored duration is irrelevant: applying services re-apply the effect on a short interval
 * while the player is inside the region, so only type and amplifier round-trip.
 */
@NullMarked
public final class PotionEffectSetFlag extends Flag<Set<PotionEffect>> {

    private static final int DURATION = 200;

    public PotionEffectSetFlag(final String name) {
        super(name);
    }

    @Override
    public @Nullable Set<PotionEffect> parse(final String input) {
        return read(input.split(","), new ArrayList<>(0));
    }

    /**
     * @param unreadable collects the tokens naming no known effect, for the caller to report
     */
    private static @Nullable Set<PotionEffect> read(final String[] tokens, final List<String> unreadable) {
        final Set<PotionEffect> effects = new LinkedHashSet<>();
        for (final String raw : tokens) {
            final String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            final int colon = token.indexOf(':');
            final PotionEffectType type = effectType(colon < 0 ? token : token.substring(0, colon).trim());
            if (type == null) {
                unreadable.add(token);
                continue;
            }
            int amplifier = 0;
            if (colon >= 0) {
                try {
                    amplifier = Math.max(0, Integer.parseInt(token.substring(colon + 1).trim()));
                } catch (final NumberFormatException e) {
                    amplifier = 0;
                }
            }
            effects.add(new PotionEffect(type, DURATION, amplifier, true, false, false));
        }
        return effects.isEmpty() ? null : effects;
    }

    @Override
    public @Nullable Set<PotionEffect> unmarshal(final Object stored) {
        if (!(stored instanceof Collection<?> list)) {
            return parse(String.valueOf(stored));
        }
        final String[] tokens = new String[list.size()];
        int i = 0;
        for (final Object element : list) {
            tokens[i++] = String.valueOf(element);
        }
        final List<String> unreadable = new ArrayList<>(0);
        final Set<PotionEffect> effects = read(tokens, unreadable);
        DroppedValues.report(getName(), unreadable);
        return effects;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object marshal(final Set<PotionEffect> value) {
        final List<String> tokens = new ArrayList<>(value.size());
        for (final PotionEffect effect : value) {
            tokens.add(effect.getType().getName() + ":" + effect.getAmplifier());
        }
        return tokens;
    }

    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType effectType(final String name) {
        return PotionEffectType.getByName(name);
    }
}
