package com.caseware.fanout.adapter.out.persistence;

import java.nio.file.Files;

import com.caseware.fanout.application.service.*;
import com.caseware.fanout.application.port.out.*;
import org.junit.jupiter.api.Test;
import com.caseware.fanout.application.exception.InventoryContractException;
import com.caseware.fanout.domain.exception.PublicationConflictException;
import com.caseware.fanout.domain.exception.RegionMismatchException;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;

class PlannerRecoveryIT {

    @TempDir
    Path directory;

    @Test
    void selectsOnlyNecessaryReadsAndLeavesHealthyBaselineQueryable() {
        try (Fixture f = new Fixture()) {
            Engagement healthy = new Engagement("EU", "firm", "healthy");
            f.store.project(healthy, new Metadata(9, "v3"));
            plan(f.store, f.clock, List.of(new Candidate(healthy, 9, false), candidate("firm", "missing")));
            assertEquals(1, f.store.tasks(100).size());
            assertEquals(new Metadata(9, "v3"), f.store.metadata(healthy));
            assertTrue(f.store.run(PUB).finished());
            // pending slow work does not erase the healthy baseline
            assertEquals(1, f.store.messageCount());
        }
    }

    @Test
    void pageCursorTasksAndOutboxRollbackTogetherThenResumeFromDisk() {
        String url = TestDatabase.newUrl();
        AtomicBoolean fail = new AtomicBoolean(true);
        MutableClock clock = new MutableClock();
        List<Candidate> candidates = List.of(candidate("a", "1"), candidate("a", "2"), candidate("b", "3"));
        try (PersistenceTestContext store = new PersistenceTestContext(url, "EU", policy(), name -> {
            if (name.equals("savePage") && fail.getAndSet(false)) {
                throw new IllegalStateException("Simulated transaction failure");
            }
        })) {
            FanOutPlanner planner = new FanOutPlanner(store.store(), inventory(candidates), clock, 2);
            assertThrows(IllegalStateException.class, () -> planner.step(PUB));
            assertNull(store.store().run(PUB).cursor());
            assertTrue(store.store().tasks(100).isEmpty());
            assertTrue(store.store().unpublished(100).isEmpty());
            assertTrue(planner.step(PUB));
            assertEquals("2", store.store().run(PUB).cursor());
        }
        try (PersistenceTestContext restarted = new PersistenceTestContext(url, "EU", policy())) {
            assertFalse(new FanOutPlanner(restarted.store(), inventory(candidates), clock, 2).step(PUB));
            assertEquals(3, restarted.store().tasks(100).size());
            assertEquals(3, restarted.store().unpublished(100).size());
            assertFalse(new FanOutPlanner(restarted.store(), inventory(candidates), clock, 2).step(PUB));
            assertEquals(3, restarted.store().tasks(100).size());
        }
    }

    @Test
    void concurrentPlannersAndDuplicatePublicationsCreateOneTaskPerFile() throws Exception {
        try (Fixture f = new Fixture();
            PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
            ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Inventory source = (p, cursor, limit) -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return new Page(List.of(candidate("a", "1"), candidate("b", "2")), null);
            };
            Future<Boolean> a = executor.submit(() -> new FanOutPlanner(f.store, source, f.clock, 2).step(PUB));
            Future<Boolean> b = executor.submit(() -> new FanOutPlanner(second.store(), source, f.clock, 2).step(PUB));
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(2, f.store.tasks(100).size());
            assertEquals(2, f.store.unpublished(100).size());
            assertThrows(PublicationConflictException.class, () -> f.store.start(new Publication(PUB.id(), "EU", "audit", "DIFFERENT")));
        }
    }

    @Test
    void crashAfterQueueSendBeforeOutboxMarkProducesSafeDuplicate() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (Fixture f = new Fixture(policy(), TestDatabase.newUrl(), name -> {
            if (name.equals("published") && fail.getAndSet(false)) {
                throw new IllegalStateException("Lost dispatch acknowledgement");
            }
        })) {
            new FanOutPlanner(f.store, inventory(List.of(candidate("a", "1"))), f.clock, 2).step(PUB);
            OutboxDispatcher dispatcher = new OutboxDispatcher(f.store, f.store, f.clock);
            assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(10));
            assertEquals(1, f.store.messageCount());
            dispatcher.dispatch(10);
            assertEquals(2, f.store.messageCount());
            f.worker().tick();
            f.worker().tick();
            assertEquals(1, f.gateway.started.size());
            assertEquals(State.DONE, f.store.tasks(10).getFirst().state());
            assertEquals(0, f.store.messageCount());
        }
    }

    @Test
    void inventoryBoundsAndRegionalIsolationAreEnforced() {
        try (Fixture f = new Fixture()) {
            assertThrows(InventoryContractException.class, () -> new FanOutPlanner(f.store, (p, c, l) -> new Page(List.of(candidate("a", "1"), candidate("b", "2")), null), f.clock, 1).step(PUB));
            assertThrows(RegionMismatchException.class, () -> new FanOutPlanner(f.store, inventory(List.of(new Candidate(new Engagement("Canada", "a", "1"), 1, true))), f.clock, 1).step(PUB));
            assertTrue(f.store.tasks(10).isEmpty());
            assertThrows(RegionMismatchException.class, () -> f.store.metadata(new Engagement("Canada", "a", "1")));
        }
    }

    @Test
    void brokerVisibilityAndReceiptFencingSurviveRedelivery() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            Delivery old = f.store.receive(f.clock.instant(), f.policy.lease()).orElseThrow();
            assertTrue(f.store.receive(f.clock.instant(), f.policy.lease()).isEmpty());
            f.clock.advance(f.policy.lease().plusMillis(1));
            Delivery current = f.store.receive(f.clock.instant(), f.policy.lease()).orElseThrow();
            assertEquals(old.id(), current.id());
            assertNotEquals(old.receipt(), current.receipt());
            assertFalse(f.store.acknowledge(old));
            assertTrue(f.store.acknowledge(current));
        }
    }

    @Test
    void staleClaimCannotCommitAfterRecoveryTakesOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            String id = f.store.tasks(10).getFirst().id();
            Claim old = f.store.claim(id, f.clock.instant(), f.policy.lease()).orElseThrow();
            old = f.store.admit(old, f.clock.instant()).orElseThrow();
            f.clock.advance(f.policy.lease().plusMillis(1));
            Claim current = f.store.claim(id, f.clock.instant(), f.policy.lease()).orElseThrow();
            assertNotEquals(old.token(), current.token());
            assertFalse(f.store.complete(old, RemoteResult.success(1, "bad"), f.clock.instant(), f.clock.instant()));
            assertEquals(1, f.store.activeCalls());
            assertTrue(f.store.complete(current, RemoteResult.success(1, "v3"), f.clock.instant(), f.clock.instant()));
            assertEquals("v3", f.store.metadata(candidate("a", "1").engagement()).appliedVersion());
        }
    }

    @Test
    void actualProcessCrashRetainsReservationAndQueueOnDisk() throws Exception {
        String url = TestDatabase.newUrl();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, CrashProcess.class.getName(), url).redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Crash helper did not finish");
        }
        assertEquals(23, process.exitValue(), Files.readString(directory.resolve("child.log")));
        try (Fixture f = new Fixture(policy(), url, ignored -> {
        })) {
            Task before = f.store.tasks(10).getFirst();
            assertNotNull(before.operationId());
            assertEquals(1, f.store.activeCalls());
            f.clock.advance(f.policy.lease().plusSeconds(1));
            f.worker().tick();
            assertEquals(List.of(before.operationId()), f.gateway.started);
            assertEquals(1, f.store.task(before.id()).attempts());
            assertEquals(State.DONE, f.store.task(before.id()).state());
            assertEquals(0, f.store.activeCalls());
        }
    }
}
