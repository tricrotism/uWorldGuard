package com.tricrotism.uworldguard.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.NullMarked;

/**
 * Plugin configuration, loaded from {@code config.yml}.
 */
@NullMarked
public final class Settings {

    private String storageType = "yaml";
    private Material wandItem = Material.WOODEN_AXE;
    private int autoSaveMinutes = 5;

    private boolean sqlEnabled = false;
    private String sqlUrl = "jdbc:sqlite:plugins/uWorldGuard/regions.db";
    private String sqlUser = "";
    private String sqlPassword = "";

    public void load(final FileConfiguration config) {
        storageType = config.getString("storage.type", storageType);
        autoSaveMinutes = config.getInt("storage.auto-save-minutes", autoSaveMinutes);

        final String wand = config.getString("selection.wand-item", wandItem.name());
        final Material parsed = Material.matchMaterial(wand);
        if (parsed != null) {
            wandItem = parsed;
        }

        sqlEnabled = config.getBoolean("storage.sql.enabled", sqlEnabled);
        sqlUrl = config.getString("storage.sql.url", sqlUrl);
        sqlUser = config.getString("storage.sql.user", sqlUser);
        sqlPassword = config.getString("storage.sql.password", sqlPassword);
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
