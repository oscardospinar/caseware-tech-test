package com.caseware.fanout.adapter.out.persistence;

import org.springframework.dao.DataIntegrityViolationException;

import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import liquibase.Liquibase;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

class DatabaseMigrationIT {

    @TempDir
    Path directory;

    private String freshDatabase() {
        return TestDatabase.newUrl();
    }

    private String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void freshSchemaHasVersionHistoryKeysAndIndexes() {
        try (PersistenceTestContext database = new PersistenceTestContext(freshDatabase(), "EU", policy())) {
            JdbcTemplate sql = new JdbcTemplate(database.dataSource());
            assertEquals(List.of("001-baseline", "002-indexes"), sql.queryForList("SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class));
            // Liquibase records a checksum and stable changelog identity.
            assertNotNull( sql.queryForObject("SELECT md5sum FROM databasechangelog WHERE id='001-baseline'", String.class));
            assertEquals("db/changelog/postgresql/db.changelog-master.xml", sql.queryForObject("SELECT filename FROM databasechangelog WHERE id='001-baseline'", String.class));
            assertEquals(3, sql.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN ('messages_due','tasks_active_firm','outbox_pending')", Integer.class));
            assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM databasechangeloglock WHERE locked=TRUE", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> sql.update("INSERT INTO outbox(task_id) VALUES('missing-task')"));
            plan(database.store(), new MutableClock(), List.of(candidate("a", "1")));
            assertThrows(DataIntegrityViolationException.class, () -> sql.update("""
                INSERT INTO tasks(id,run_id,firm,engagement)
                SELECT 'duplicate',run_id,firm,engagement FROM tasks
                """));
        }
    }

    @Test
    void restartDoesNotReapplyChangesOrLosePendingWork() {
        String url = TestDatabase.newUrl();
        List<String> checksums;
        String operation;
        try (PersistenceTestContext database = new PersistenceTestContext(url, "EU", policy())) {
            SpringDataStore store = database.store();
            plan(store, new MutableClock(), List.of(candidate("a", "1")));
            Claim claim = store.claim(store.tasks(10).getFirst().id(), START, policy().lease()).orElseThrow();
            operation = store.admit(claim, START).orElseThrow().task().operationId();
            checksums = new JdbcTemplate(database.dataSource()).queryForList("SELECT md5sum FROM databasechangelog ORDER BY orderexecuted", String.class);
        }
        try (PersistenceTestContext database = new PersistenceTestContext(url, "EU", policy())) {
            assertEquals(checksums, new JdbcTemplate(database.dataSource()).queryForList("SELECT md5sum FROM databasechangelog ORDER BY orderexecuted", String.class));
            assertEquals(operation, database.store().tasks(10).getFirst().operationId());
            assertEquals(1, database.store().activeCalls());
            assertEquals(1, database.store().messageCount());
        }
    }

    @Test
    void upgradesBaselineWithoutLosingReservationsReceiptsOrMetadata() throws Exception {
        String url = freshDatabase();
        // Apply only the first PostgreSQL migration, then verify an incremental upgrade preserves work.
        try (Connection connection = TestDatabase.connect(url);
                Liquibase liquibase = new Liquibase("db/changelog/postgresql/db.changelog-master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(1, new Contexts(), new LabelExpression());
        }
        try (Connection connection = TestDatabase.connect(url);
            Statement statement = connection.createStatement()) {
            try (PreparedStatement seed = connection.prepareStatement("INSERT INTO control(id,region,config) VALUES(1,'EU',?)")) {
                seed.setString(1, policy().toString());
                seed.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO runs(id,pub_id,product,target,cursor_value) VALUES('r','publication-1','audit','v7','cursor-2')");
            statement.executeUpdate("INSERT INTO firms(firm,last_turn) VALUES('a',12)");
            statement.executeUpdate("""
                INSERT INTO tasks(id,run_id,region,firm,engagement,required_revision,state,attempts,operation_id,due_at,token,lease_until)
                VALUES('t','r','EU','a','1',1,'RUNNING',1,'t:1',0,4,999)
                """);
            statement.executeUpdate("INSERT INTO outbox(task_id,published) VALUES('t',TRUE)");
            statement.executeUpdate("INSERT INTO messages(task_id,visible_at,receipt) VALUES('t',999,'original-receipt')");
            statement.executeUpdate("INSERT INTO metadata VALUES('a','1',5,'v5'),('b','1',7,'v7')");
            try (PersistenceTestContext database = new PersistenceTestContext(url, "EU", policy())) {
                SpringDataStore store = database.store();
                assertEquals("cursor-2", store.run(PUB).cursor());
                assertEquals("t:1", store.task("t").operationId());
                assertEquals(1, store.activeCalls());
                assertTrue(store.unpublished(10).isEmpty());
                assertEquals(new Metadata(5, "v5"), store.metadata(new Engagement("EU", "a", "1")));
                assertEquals(new Metadata(7, "v7"), store.metadata(new Engagement("EU", "b", "1")));
                JdbcTemplate sql = new JdbcTemplate(database.dataSource());
                assertEquals("original-receipt", sql.queryForObject("SELECT receipt FROM messages", String.class));
                assertEquals(4, sql.queryForObject("SELECT token FROM tasks", Integer.class));
                assertEquals(2, sql.queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
                // Identity generation continues past the legacy row.
                store.send("t", START);
                assertEquals(2, store.messageCount());
            }
        }
    }

    @Test
    void partialUnversionedSchemaIsRejectedWithoutReplacingTables() throws Exception {
        String url = freshDatabase();
        try (Connection connection = TestDatabase.connect(url);
            Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE control(id INT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO control VALUES(1)");
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new PersistenceTestContext(url, "EU", policy()));
            assertTrue(messages(failure).toLowerCase().contains("already exists"));
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='tasks'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM control")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void checksumMismatchStopsStartupWithoutDiscardingJobs() {
        String url = freshDatabase();
        try (PersistenceTestContext database = new PersistenceTestContext(url, "EU", policy())) {
            plan(database.store(), new MutableClock(), List.of(candidate("a", "1")));
            JdbcTemplate sql = new JdbcTemplate(database.dataSource());
            sql.update("UPDATE databasechangelog SET md5sum='9:00000000000000000000000000000000' WHERE id='001-baseline'");
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new PersistenceTestContext(url, "EU", policy()));
            assertTrue(messages(failure).toLowerCase().contains("check sum"), messages(failure));
            assertEquals(1, database.store().tasks(10).size());
        }
    }
}
