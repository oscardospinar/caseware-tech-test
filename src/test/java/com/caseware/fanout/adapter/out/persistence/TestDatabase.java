package com.caseware.fanout.adapter.out.persistence;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Real PostgreSQL, isolated schema per fixture; no silent fallback or skipped integration tests. */
public final class TestDatabase {
    private static final Logger LOGGER = System.getLogger(TestDatabase.class.getName());
    private static final Properties DEFAULTS = loadDefaults();
    private static final String URL = setting("TEST_DATABASE_URL");
    private static final Set<String> SCHEMAS = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::cleanup, "test-schema-cleanup"));
    }

    private TestDatabase() {
    }

    public static String username() {
        return setting("TEST_DATABASE_USERNAME");
    }

    public static String password() {
        return setting("TEST_DATABASE_PASSWORD");
    }

    private static String setting(String name) {
        return System.getenv().getOrDefault(name, DEFAULTS.getProperty(name));
    }

    private static Properties loadDefaults() {
        try (InputStream resource = TestDatabase.class.getResourceAsStream("/test-database.properties")) {
            if (resource == null) {
                throw new IllegalStateException("Missing test-database.properties on the test classpath");
            }
            Properties defaults = new Properties();
            defaults.load(resource);
            return defaults;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read local test database configuration", e);
        }
    }

    public static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, username(), password());
    }

    public static String newUrl() {
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect(URL); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            SCHEMAS.add(schema);
            return URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create PostgreSQL test schema. Start PostgreSQL with "
                    + "'docker compose up -d --wait postgres' and configure TEST_DATABASE_URL, "
                    + "TEST_DATABASE_USERNAME and TEST_DATABASE_PASSWORD as shown in README.md. "
                    + "Liquibase has not run yet; inspect the original cause for connection or permission errors.", e);
        }
    }

    private static void cleanup() {
        for (String schema : SCHEMAS) {
            try (Connection connection = connect(URL); Statement statement = connection.createStatement()) {
                // Names come only from newUrl(), never from caller input or an existing schema inventory.
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Test schema cleanup failed. Type: {0}", e.getClass().getSimpleName());
            }
        }
    }
}
