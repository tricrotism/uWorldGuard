package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.region.RegionManager;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NullMarked;

import java.sql.*;
import java.util.Enumeration;
import java.util.logging.Level;

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
            final boolean mysql = isMySql(connection);
            statement.execute("CREATE TABLE IF NOT EXISTS uwg_regions ("
                + "world VARCHAR(255) PRIMARY KEY, data " + (mysql ? "LONGTEXT" : "TEXT") + " NOT NULL)");
            if (mysql) {
                statement.execute("ALTER TABLE uwg_regions MODIFY data LONGTEXT NOT NULL");
            }
        }
    }

    /**
     * MySQL and MariaDB are the two backends whose SQL differs here. Their {@code TEXT} holds 65,535
     * bytes, and one world's document passes that with a few hundred regions: strict mode then
     * refuses every save, and non-strict mode truncates the document, so the next load cannot parse
     * it and saving is disabled for that world. Tables created before this are widened in place,
     * which keeps every row. They also take {@code ON DUPLICATE KEY} rather than {@code ON CONFLICT}.
     */
    private static boolean isMySql(final Connection connection) throws SQLException {
        final String product = connection.getMetaData().getDatabaseProductName();
        return "MySQL".equalsIgnoreCase(product) || "MariaDB".equalsIgnoreCase(product);
    }

    /**
     * Update-then-insert for a backend without {@code ON CONFLICT}, wrapped in a transaction so the
     * two statements cannot interleave with another save's.
     */
    private static void saveInTransaction(
        final Connection connection, final String worldName, final String data
    ) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement(
                "UPDATE uwg_regions SET data = ? WHERE world = ?")) {
                update.setString(1, data);
                update.setString(2, worldName);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO uwg_regions (world, data) VALUES (?, ?)")) {
                        insert.setString(1, worldName);
                        insert.setString(2, data);
                        insert.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (final SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Whether {@code driverClass} is the copy this plugin's own classpath supplies. Comparing the
     * driver's classloader against ours is not enough: Paper hands a plugin's downloaded libraries to
     * a loader beside the plugin's rather than above it, so the driver's loader is never literally
     * this class's. Resolving the name through our classpath and comparing the {@link Class} objects
     * answers it exactly — another plugin's copy of the same driver is a different class, and stays
     * registered. The second check leaves a server-supplied driver alone for the same reason.
     */
    private static boolean loadedByThisPlugin(final Class<?> driverClass) {
        final String name = driverClass.getName();
        try {
            if (Class.forName(name, false, SqlRegionStore.class.getClassLoader()) != driverClass) {
                return false;
            }
        } catch (final ClassNotFoundException e) {
            return false;
        }
        try {
            Bukkit.class.getClassLoader().loadClass(name);
            return false;
        } catch (final ClassNotFoundException e) {
            return true;
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

    /**
     * Writes the world's document, inserting the row if it is not there yet.
     *
     * <p>One statement rather than UPDATE-then-INSERT: split in two, two saves for the same world
     * that both found no row would both go on to insert one, and the second would fail on the
     * primary key. SQLite and Postgres take {@code ON CONFLICT}, MySQL and MariaDB take
     * {@code ON DUPLICATE KEY}, so the fallback below covers a backend that takes neither rather
     * than the race.
     */
    @Override
    public void save(final String worldName, final RegionManager manager) throws Exception {
        final String data = serializer.toYaml(manager);
        try (Connection connection = connect()) {
            final String upsertSql = isMySql(connection)
                ? "INSERT INTO uwg_regions (world, data) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE data = VALUES(data)"
                : "INSERT INTO uwg_regions (world, data) VALUES (?, ?)"
                + " ON CONFLICT(world) DO UPDATE SET data = excluded.data";
            try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                upsert.setString(1, worldName);
                upsert.setString(2, data);
                upsert.executeUpdate();
            } catch (final SQLException e) {
                saveInTransaction(connection, worldName, data);
            }
        }
    }

    private Connection connect() throws SQLException {
        return user.isEmpty()
            ? DriverManager.getConnection(url)
            : DriverManager.getConnection(url, user, password);
    }

    /**
     * Drops the drivers this plugin supplied out of {@link DriverManager}'s JVM-global registry. A
     * JDBC driver registers itself the first time it is asked for a connection, and that registration
     * outlives the plugin — holding the driver class, and through it the classloader and every static
     * in it, alive for as long as the server runs.
     */
    @Override
    public void close() {
        final Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            final Driver driver = drivers.nextElement();
            if (!loadedByThisPlugin(driver.getClass())) {
                continue;
            }
            try {
                DriverManager.deregisterDriver(driver);
            } catch (final SQLException e) {
                Bukkit.getLogger().log(Level.WARNING,
                    "Failed to deregister JDBC driver " + driver.getClass().getName(), e);
            }
        }
    }
}
