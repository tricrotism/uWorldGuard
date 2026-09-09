package com.tricrotism.uworldguard.config;

import com.tricrotism.uworldguard.util.VerboseLogging;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NullMarked;

import java.util.Locale;

/**
 * Plugin configuration, loaded from {@code config.yml}.
 */
@NullMarked
public final class Settings {

    /**
     * How region entry/exit is detected for players.
     */
    public enum MovementMode {
        /**
         * Per-{@code PlayerMoveEvent}. Exact, and a denied crossing is cancelled before it happens.
         */
        EVENT,
        /**
         * Polled on a fixed interval. Cheaper at high player counts; a denied crossing is undone
         * after the fact by teleporting the player back, and can lag by up to one interval.
         */
        TASK
    }

    private String storageType = "yaml";
    private Material wandItem = Material.WOODEN_AXE;
    private int autoSaveMinutes = 5;

    private MovementMode movementMode = MovementMode.EVENT;
    private int movementTaskTicks = 4;

    private boolean updateCheck = true;
    private boolean verboseLogging = false;

    private boolean sqlEnabled = false;
    private String sqlUrl = "jdbc:sqlite:plugins/uWorldGuard/regions.db";
    private String sqlUser = "";
    private String sqlPassword = "";

    public void load(final FileConfiguration config) {
        storageType = config.getString("storage.type", storageType);
        autoSaveMinutes = config.getInt("storage.auto-save-minutes", autoSaveMinutes);

        final String mode = config.getString("movement.mode", movementMode.name());
        try {
            movementMode = MovementMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            movementMode = MovementMode.EVENT;
        }
        movementTaskTicks = Math.max(1, config.getInt("movement.task-interval-ticks", movementTaskTicks));

        final String wand = config.getString("selection.wand-item", wandItem.name());
        final Material parsed = Material.matchMaterial(wand);
        if (parsed != null) {
            wandItem = parsed;
        }

        updateCheck = config.getBoolean("updates.check", updateCheck);

        verboseLogging = config.getBoolean("logging.verbose", verboseLogging);
        VerboseLogging.set(verboseLogging);

        sqlEnabled = config.getBoolean("storage.sql.enabled", sqlEnabled);
        sqlUrl = config.getString("storage.sql.url", sqlUrl);
        sqlUser = config.getString("storage.sql.user", sqlUser);
        sqlPassword = config.getString("storage.sql.password", sqlPassword);
    }

    public boolean verboseLogging() {
        return verboseLogging;
    }

    public boolean updateCheck() {
        return updateCheck;
    }

    public boolean isSqlEnabled() {
        return sqlEnabled && "sql".equalsIgnoreCase(storageType);
    }

    public String storageType() {
        return storageType;
    }

    public Material wandItem() {
        return wandItem;
    }

    public int autoSaveMinutes() {
        return autoSaveMinutes;
    }

    public MovementMode movementMode() {
        return movementMode;
    }

    public int movementTaskTicks() {
        return movementTaskTicks;
    }

    public String sqlUrl() {
        return sqlUrl;
    }

    public String sqlUser() {
        return sqlUser;
    }

    public String sqlPassword() {
        return sqlPassword;
    }
}
