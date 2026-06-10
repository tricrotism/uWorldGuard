package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.region.RegionManager;
import org.jspecify.annotations.NullMarked;

import java.sql.*;

/**
 * JDBC-backed store: one row per world holding the same YAML document {@link YamlRegionStore}
 * writes to disk, so the two backends are interchangeable. Disabled by default; intended for
 * SQLite (the driver is loaded at boot) but works with any JDBC URL whose driver is present.
 *
 * <p>Standard {@code java.sql} only — no compile-time driver dependency. All calls run on the
 * async scheduler, so blocking I/O here never touches a region thread.
 */
@NullMarked
public final class SqlRegionStore implements RegionStore {

    private final String url;
    private final String user;
    private final String password;
    private final RegionSerializer serializer = new RegionSerializer();

    public SqlRegionStore(final String url, final String user, final String password) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS uwg_regions ("
                + "world VARCHAR(255) PRIMARY KEY, data TEXT NOT NULL)");
        }
    }

    @Override
    public void load(final String worldName, final RegionManager manager) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT data FROM uwg_regions WHERE world = ?")) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    serializer.fromYaml(rs.getString(1), manager);
                }
            }
        }
    }

    @Override
    public void save(final String worldName, final RegionManager manager) throws Exception {
        final String data = serializer.toYaml(manager);
        try (Connection connection = connect()) {
            try (PreparedStatement update = connection.prepareStatement(
                "UPDATE uwg_regions SET data = ? WHERE world = ?")) {
                update.setString(1, data);
                update.setString(2, worldName);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO uwg_regions (world, data) VALUES (?, ?)")) {
                insert.setString(1, worldName);
                insert.setString(2, data);
                insert.executeUpdate();
            }
        }
    }

    private Connection connect() throws SQLException {
        return user.isEmpty()
            ? DriverManager.getConnection(url)
            : DriverManager.getConnection(url, user, password);
    }
}
