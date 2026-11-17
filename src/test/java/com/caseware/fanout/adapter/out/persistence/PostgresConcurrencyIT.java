package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.Run;
import com.caseware.fanout.domain.model.Task;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PostgresConcurrencyIT {
    @Test
    void admissionLockDoesNotBlockReadsPollingClaimsOrHeartbeat() throws Exception {
        try (Fixture f = new Fixture();
                Connection blocker = TestDatabase.connect(f.url);
                Statement statement = blocker.createStatement();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2")));
            Delivery delivery = f.store.receive(START, f.policy.lease()).orElseThrow();
            Claim claim = f.store.claim(delivery.taskId(), START, f.policy.lease()).orElseThrow();
            Claim active = f.store.admit(claim, START).orElseThrow();
            blocker.setAutoCommit(false);
            statement.execute("SELECT * FROM control WHERE id=1 FOR UPDATE");
            try {
                Future<Boolean> independent = executor.submit(() -> {
                    assertEquals(active.task().operationId(), f.store.task(delivery.taskId()).operationId());
                    assertTrue(f.store.renew(active, START, f.policy.lease()));
                    assertTrue(f.store.renew(delivery, START, f.policy.lease()));
                    Delivery next = f.store.receive(START, f.policy.lease()).orElseThrow();
                    assertTrue(f.store.claim(next.taskId(), START, f.policy.lease()).isPresent());
                    f.store.project(new Engagement("EU", "other", "file"), new Metadata(2, "v2"));
                    return f.store.activeCalls() == 1;
                });
                assertTrue(independent.get(2, TimeUnit.SECONDS));
            } finally {
                blocker.rollback();
            }
        }
    }

    @Test
    void consumersSkipLockedMessagesInsteadOfWaitingForThem() throws Exception {
        try (Fixture f = new Fixture();
                Connection blocker = TestDatabase.connect(f.url);
                Statement statement = blocker.createStatement();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2")));
            blocker.setAutoCommit(false);
            try (ResultSet row = statement.executeQuery("SELECT id FROM messages ORDER BY id LIMIT 1 FOR UPDATE")) {
                assertTrue(row.next());
                long locked = row.getLong(1);
                try {
                    Future<Delivery> received = executor.submit(() -> f.store.receive(START, f.policy.lease()).orElseThrow());
                    assertNotEquals(locked, received.get(2, TimeUnit.SECONDS).id());
                } finally {
                    blocker.rollback();
                }
            }
        }
    }

    @Test
    void separateReplicasCannotReceiveTheSameMessage() throws Exception {
        try (Fixture f = new Fixture();
                PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2")));
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Delivery> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return f.store.receive(START, f.policy.lease()).orElseThrow();
            });
            Future<Delivery> other = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.store().receive(START, f.policy.lease()).orElseThrow();
            });
            assertNotEquals(first.get(5, TimeUnit.SECONDS).id(), other.get(5, TimeUnit.SECONDS).id());
        }
    }

    @Test
    void concurrentProjectionOfAbsentMetadataCannotRegressRevision() throws Exception {
        try (Fixture f = new Fixture();
                PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Engagement engagement = new Engagement("EU", "firm", "file");
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                f.store.project(engagement, new Metadata(2, "v2"));
                return null;
            });
            Future<?> other = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                second.store().project(engagement, new Metadata(9, "v9"));
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);
            assertEquals(new Metadata(9, "v9"), f.store.metadata(engagement));
        }
    }

    @Test
    void unrelatedPublicationCanCheckpointWhileAnotherIsLocked() throws Exception {
        try (Fixture f = new Fixture();
                Connection blocker = TestDatabase.connect(f.url);
                Statement statement = blocker.createStatement();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            f.store.start(PUB);
            Publication other = new Publication("other", "EU", "audit", "v7");
            Run run = f.store.start(other);
            blocker.setAutoCommit(false);
            statement.execute("SELECT * FROM runs WHERE pub_id='publication-1' FOR UPDATE");
            try {
                Future<Boolean> saved = executor.submit(() -> f.store.savePage(run,
                        new Page(List.of(candidate("b", "2")), null), START));
                assertTrue(saved.get(2, TimeUnit.SECONDS));
            } finally {
                blocker.rollback();
            }
        }
    }

    @Test
    void productionClaimsUseDatabaseTimeDespiteSkewedHostTimestamp() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            Task task = f.store.tasks(10).getFirst();
            SpringDataStore production = f.database.productionStore();
            Claim active = production.claim(task.id(), Instant.EPOCH, f.policy.lease()).orElseThrow();
            assertTrue(production.renew(active, Instant.EPOCH, f.policy.lease()));
            // A host far in the future cannot steal a lease that is still valid in PostgreSQL.
            assertTrue(production.claim(task.id(), Instant.parse("2099-01-01T00:00:00Z"), f.policy.lease()).isEmpty());
        }
    }
}
