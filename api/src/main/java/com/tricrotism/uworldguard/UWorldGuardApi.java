package com.tricrotism.uworldguard;

import com.tricrotism.uworldguard.region.RegionContainer;
import com.tricrotism.uworldguard.region.RegionQuery;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

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

    private UWorldGuardApi() {
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
