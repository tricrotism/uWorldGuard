package com.tricrotism.uworldguard;

import com.tricrotism.uworldguard.region.RegionContainer;
import com.tricrotism.uworldguard.region.RegionEditor;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Static entry point for other plugins.
 *
 * <pre>{@code
 * RegionQuery query = UWorldGuardApi.regionContainer().createQuery();
 * boolean canBuild = query.testBuild(location, player);
 * }</pre>
 *
 * <p>Available from the moment uWorldGuard enables until it disables; declare a dependency on
 * {@code uWorldGuard} in your {@code paper-plugin.yml} to guarantee ordering. The container is
 * also registered with Bukkit's services manager ({@code load(RegionContainer.class)}) if you
 * prefer that lookup style.
 */
@NullMarked
public final class UWorldGuardApi {

    private static volatile @Nullable RegionContainer container;
    private static volatile Predicate<Player> bypass = _ -> false;

    private UWorldGuardApi() {}

    /**
     * Whether {@code player} has {@code /uwg bypass} switched on and still holds the permission for
     * it. uWorldGuard's own protection ignores such a player, and a plugin enforcing protection of its
     * own should do the same, so staff are not stopped by one plugin and waved through by another.
     *
     * <p>False while uWorldGuard is not enabled. Safe from any thread.
     */
    public static boolean hasBypass(final Player player) {
        return bypass.test(player);
    }

    /**
     * Internal: bound by the uWorldGuard plugin on enable, reset on disable. Other plugins must never
     * call this.
     */
    public static void bindBypass(final @Nullable Predicate<Player> check) {
        bypass = check == null ? _ -> false : check;
    }

    /**
     * The region container.
     *
     * @throws IllegalStateException if uWorldGuard is not enabled
     */
    public static RegionContainer regionContainer() {
        final RegionContainer c = container;
        if (c == null) {
            throw new IllegalStateException("uWorldGuard is not enabled");
        }
        return c;
    }

    /**
     * Convenience for {@code regionContainer().createQuery()}.
     *
     * @throws IllegalStateException if uWorldGuard is not enabled
     */
    public static RegionQuery createQuery() {
        return regionContainer().createQuery();
    }

    /**
     * Convenience for {@code regionContainer().editor(world)}: an editor for the world's regions, or
     * {@code null} while they are not loaded.
     *
     * @throws IllegalStateException if uWorldGuard is not enabled
     */
    public static @Nullable RegionEditor editor(final World world) {
        return regionContainer().editor(world);
    }

    /**
     * Whether the API is currently usable (uWorldGuard is enabled).
     */
    public static boolean isAvailable() {
        return container != null;
    }

    /**
     * Internal: bound by the uWorldGuard plugin on enable ({@code null} on disable).
     * Other plugins must never call this.
     */
    public static void bind(final @Nullable RegionContainer instance) {
        container = instance;
    }
}
