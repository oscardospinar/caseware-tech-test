package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.domain.policy.Policy;
import com.caseware.fanout.application.service.*;
import com.caseware.fanout.application.port.out.*;
import com.caseware.fanout.adapter.out.scheduling.ScheduledLeaseHeartbeat;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.RemoteState;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;

class FanOutWorkerIT {

    @Test
    void interruptedGatewayRetainsReservationAndRestoresInterruptStatus() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            f.gateway.behavior = (operation, engagement) -> {
                throw new InterruptedException("Remote response interrupted");
            };
            try {
                assertTrue(f.worker().tick());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                // Do not leak the deliberately restored interrupt into JDBC or the test runner.
                Thread.interrupted();
            }
            Task pending = f.store.tasks(10).getFirst();
            assertEquals("REMOTE_UNCERTAIN", pending.errorCode());
            assertEquals(1, f.store.activeCalls());
            f.gateway.finish(pending.operationId(), RemoteResult.success(1, "v3"));
            f.clock.advance(f.policy.pollDelay());
            f.worker().tick();
            assertEquals(State.DONE, f.store.task(pending.id()).state());
            assertEquals(1, f.gateway.invocations.get());
            assertEquals(1, f.store.task(pending.id()).attempts());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void heartbeatContinuesAfterFailedRenewalWithoutReleasingReservation() throws Exception {
        AtomicInteger renewalAttempts = new AtomicInteger();
        CountDownLatch renewed = new CountDownLatch(1);
        Policy shortLease = new Policy(1, 1, Duration.ZERO, 3, Duration.ofMillis(900),
                Duration.ofMillis(50), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
        try (Fixture f = new Fixture(shortLease, TestDatabase.newUrl(), name -> {
            if (name.equals("renewClaim") && renewalAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Temporary renewal failure");
            }
            if (name.equals("renewDelivery")) {
                renewed.countDown();
            }
        })) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            Delivery delivery = f.store.receive(f.clock.instant(), f.policy.lease()).orElseThrow();
            Claim claim = f.store.claim(delivery.taskId(), f.clock.instant(), f.policy.lease()).orElseThrow();
            Claim active = f.store.admit(claim, f.clock.instant()).orElseThrow();
            try (ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                    f.store, f.store, f.policy, f.clock, f.heartbeats, f.policy.lease().dividedBy(3), Duration.ofSeconds(5));
                    LeaseHeartbeat.Registration registration = heartbeat.start(active, delivery)) {
                assertTrue(renewed.await(5, TimeUnit.SECONDS));
                assertTrue(renewalAttempts.get() >= 2);
                assertEquals(active.task().operationId(), f.store.task(delivery.taskId()).operationId());
                assertEquals(1, f.store.activeCalls());
            }
        }
    }

    @Test
    void multipleWorkersRespectSharedAndPerFirmConcurrency() throws Exception {
        try (Fixture f = new Fixture();
            PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
            ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Candidate> files = new ArrayList<>();
            for (String firm : List.of("a", "b", "c")) {
                for (int i = 0; i < 3; i++) {
                    files.add(candidate(firm, "file-" + i));
                }
            }
            plan(f.store, f.clock, files);
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            f.gateway.behavior = (op, e) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return RemoteResult.success(1, "v3");
            };
            List<Future<Boolean>> calls = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    FanOutWorker worker = f.worker(i % 2 == 0 ? f.store : second.store());
                    calls.add(executor.submit(worker::tick));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(2, f.store.activeCalls());
                assertEquals(2, f.gateway.peak.get());
                assertEquals(1, f.gateway.firmPeak.get());
            } finally {
                release.countDown();
            }
            for (Future<Boolean> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
            f.gateway.behavior = (op, e) -> RemoteResult.success(1, "v3");
            for (int i = 0; i < 20; i++) {
                f.clock.advance(Duration.ofSeconds(2));
                f.worker().tick();
            }
            assertTrue(f.store.tasks(100).stream().allMatch(t -> t.state() == State.DONE));
            assertEquals(9, f.gateway.started.size());
            assertEquals(0, f.store.activeCalls());
            assertTrue(f.gateway.peak.get() <= 2);
        }
    }

    @Test
    void queueRotatesFirmsInsteadOfDrainingLargestFirmFirst() {
        try (Fixture f = new Fixture()) {
            List<Candidate> files = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                files.add(candidate("large", "file-" + i));
            }
            files.add(candidate("small", "only-file"));
            plan(f.store, f.clock, files);
            Delivery first = f.store.receive(f.clock.instant(), f.policy.lease()).orElseThrow();
            Delivery second = f.store.receive(f.clock.instant(), f.policy.lease()).orElseThrow();
            assertNotEquals(f.store.task(first.taskId()).engagement().firm(), f.store.task(second.taskId()).engagement().firm());
        }
    }

    @Test
    void duplicateDeliveryDoesNotRepeatRemoteOperation() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            String id = f.store.tasks(10).getFirst().id();
            f.store.send(id, f.clock.instant());
            f.worker().tick();
            f.worker().tick();
            assertEquals(1, f.gateway.started.size());
            assertEquals(1, f.store.task(id).attempts());
            assertEquals(0, f.store.messageCount());
        }
    }

    @Test
    void completionBeforeAckSurvivesAckFailure() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (Fixture f = new Fixture(policy(), TestDatabase.newUrl(), name -> {
            if (name.equals("ack") && fail.getAndSet(false)) {
                throw new IllegalStateException("Broker ack failed");
            }
        })) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            assertThrows(IllegalStateException.class, () -> f.worker().tick());
            assertEquals(State.DONE, f.store.tasks(10).getFirst().state());
            assertEquals(0, f.store.activeCalls());
            f.clock.advance(f.policy.lease().plusSeconds(1));
            f.worker().tick();
            assertEquals(1, f.gateway.started.size());
            assertEquals(0, f.store.messageCount());
        }
    }

    @Test
    void remoteSuccessBeforeLocalCommitRecoversUsingOperationStatus() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (Fixture f = new Fixture(policy(), TestDatabase.newUrl(), name -> {
            if (name.equals("complete") && fail.getAndSet(false)) {
                throw new IllegalStateException("Local commit failed");
            }
        })) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            assertThrows(IllegalStateException.class, () -> f.worker().tick());
            assertEquals(1, f.store.activeCalls());
            assertNull(f.store.metadata(candidate("a", "1").engagement()));
            f.clock.advance(f.policy.lease().plusSeconds(1));
            try (PersistenceTestContext restarted = new PersistenceTestContext(f.url, "EU", f.policy)) {
                f.worker(restarted.store()).tick();
            }
            assertEquals(State.DONE, f.store.tasks(10).getFirst().state());
            assertEquals(1, f.gateway.invocations.get());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void ambiguousTimeoutRetainsCapacityAndNeverStartsAnotherAttempt() {
        Policy single = new Policy(1, 1, Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
        try (Fixture f = new Fixture(single)) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2")));
            f.gateway.behavior = (op, e) -> {
                throw new IOException("Response lost after remote start");
            };
            f.worker().tick();
            String op = f.gateway.started.getFirst();
            assertEquals(1, f.store.activeCalls());
            for (int i = 0; i < 5; i++) {
                f.clock.advance(Duration.ofSeconds(2));
                f.worker().tick();
            }
            assertEquals(1, f.gateway.invocations.get());
            assertEquals(1, f.store.activeCalls());
            f.gateway.finish(op, RemoteResult.success(1, "v3"));
            f.gateway.behavior = (id, e) -> RemoteResult.success(1, "v3");
            for (int i = 0; i < 6; i++) {
                f.clock.advance(Duration.ofSeconds(2));
                f.worker().tick();
            }
            assertEquals(2, f.gateway.started.size());
            assertTrue(f.store.tasks(10).stream().allMatch(t -> t.state() == State.DONE && t.attempts() == 1));
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void transientFailuresBackOffThenStopAtAttemptBudget() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            f.gateway.behavior = (op, e) -> RemoteResult.retryable("UNAVAILABLE");
            f.worker().tick();
            assertEquals(1, f.gateway.started.size());
            assertFalse(f.worker().tick());
            assertEquals(START.plusSeconds(1), f.store.tasks(10).getFirst().availableAt());
            f.clock.advance(Duration.ofSeconds(1));
            f.worker().tick();
            assertEquals(2, f.gateway.started.size());
            assertEquals(START.plusSeconds(3), f.store.tasks(10).getFirst().availableAt());
            f.clock.advance(Duration.ofSeconds(2));
            f.worker().tick();
            Task task = f.store.tasks(10).getFirst();
            assertEquals(State.FAILED, task.state());
            assertEquals(3, task.attempts());
            assertEquals(3, new HashSet<>(f.gateway.started).size());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void recoveredWorkerDoesNotDuplicateAnOperationStillRunningOnOldWorker() throws Exception {
        try (Fixture f = new Fixture();
            PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
            ExecutorService executor = Executors.newSingleThreadExecutor()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.gateway.behavior = (op, e) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return RemoteResult.success(1, "v3");
            };
            Future<Boolean> old = executor.submit(f.worker()::tick);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.clock.advance(f.policy.lease().plusSeconds(1));
                // Reclaim expired task; status says the same remote operation is running.
                f.worker(second.store()).tick();
                assertEquals(1, f.gateway.invocations.get());
                assertEquals(1, f.store.activeCalls());
            } finally {
                release.countDown();
            }
            // Old claim cannot commit, despite receiving a successful response.
            old.get(5, TimeUnit.SECONDS);
            assertNull(f.store.metadata(candidate("a", "1").engagement()));
            f.clock.advance(Duration.ofSeconds(2));
            f.worker(second.store()).tick();
            assertEquals(State.DONE, f.store.tasks(10).getFirst().state());
            assertEquals(1, f.gateway.started.size());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void honorsRetryAfterAndDoesNotRetryPermanentErrors() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            f.gateway.behavior = (op, e) -> new RemoteResult(RemoteState.RETRYABLE_FAILURE, null, "THROTTLED", START.plusSeconds(20));
            f.worker().tick();
            assertEquals(START.plusSeconds(20), f.store.tasks(10).getFirst().availableAt());
            f.clock.advance(Duration.ofSeconds(19));
            assertFalse(f.worker().tick());
            f.clock.advance(Duration.ofSeconds(1));
            f.gateway.behavior = (op, e) -> RemoteResult.permanent("NOT_ACCESSIBLE");
            f.worker().tick();
            assertEquals(State.FAILED, f.store.tasks(10).getFirst().state());
            assertEquals(2, f.gateway.started.size());
        }
    }

    @Test
    void sharedStartRateAlsoAppliesToReservedOperations() {
        Policy spaced = new Policy(2, 1, Duration.ofSeconds(5), 3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
        try (Fixture f = new Fixture(spaced);
            PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy)) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2")));
            f.worker().tick();
            f.worker(second.store()).tick();
            assertEquals(1, f.gateway.started.size());
            // Reserved but not yet submitted; conservative crash recovery.
            assertEquals(1, f.store.activeCalls());
            f.clock.advance(Duration.ofSeconds(5));
            f.worker(second.store()).tick();
            assertEquals(2, f.gateway.started.size());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void breakerOpensAndAllowsOnlyOneRecoveryProbe() throws Exception {
        Policy breaker = new Policy(3, 1, Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(16), 1, Duration.ofSeconds(10));
        try (Fixture f = new Fixture(breaker);
            PersistenceTestContext second = new PersistenceTestContext(f.url, "EU", f.policy);
            ExecutorService executor = Executors.newSingleThreadExecutor()) {
            plan(f.store, f.clock, List.of(candidate("a", "1"), candidate("b", "2"), candidate("c", "3")));
            f.gateway.behavior = (op, e) -> RemoteResult.retryable("UNAVAILABLE");
            f.worker().tick();
            f.worker(second.store()).tick();
            assertEquals(1, f.gateway.started.size());
            assertEquals(0, f.store.activeCalls());
            f.clock.advance(Duration.ofSeconds(11));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.gateway.behavior = (op, e) -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return RemoteResult.success(1, "v3");
            };
            Future<Boolean> probe = executor.submit(f.worker()::tick);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.worker(second.store()).tick();
                assertEquals(2, f.gateway.started.size());
                assertEquals(1, f.store.activeCalls());
            } finally {
                release.countDown();
            }
            probe.get(5, TimeUnit.SECONDS);
            f.gateway.behavior = (op, e) -> RemoteResult.success(1, "v3");
            for (int i = 0; i < 5; i++) {
                f.clock.advance(Duration.ofSeconds(2));
                f.worker().tick();
            }
            assertTrue(f.store.tasks(10).stream().allMatch(t -> t.state() == State.DONE));
        }
    }

    @Test
    void olderRemoteResultCannotOverwriteLiveMetadata() {
        try (Fixture f = new Fixture()) {
            Candidate file = candidate("a", "1");
            plan(f.store, f.clock, List.of(file));
            f.gateway.behavior = (op, e) -> {
                f.store.project(e, new Metadata(9, "v6"));
                return RemoteResult.success(2, "v3");
            };
            f.worker().tick();
            assertEquals(new Metadata(9, "v6"), f.store.metadata(file.engagement()));
            assertEquals(State.SUPERSEDED, f.store.tasks(10).getFirst().state());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void withdrawalBeforeStartSkipsLoadButInFlightWithdrawalStillReconciles() {
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            f.store.withdraw(PUB);
            f.worker().tick();
            assertTrue(f.gateway.started.isEmpty());
            assertEquals(State.SUPERSEDED, f.store.tasks(10).getFirst().state());
        }
        try (Fixture f = new Fixture()) {
            plan(f.store, f.clock, List.of(candidate("a", "1")));
            f.gateway.behavior = (op, e) -> {
                f.store.withdraw(PUB);
                return RemoteResult.success(1, "v3");
            };
            f.worker().tick();
            assertEquals(State.SUPERSEDED, f.store.tasks(10).getFirst().state());
            assertEquals(0, f.store.activeCalls());
        }
    }

    @Test
    void heartbeatKeepsClaimAliveDuringLongCall() throws Exception {
        Policy shortLease = new Policy(1, 1, Duration.ZERO, 3, Duration.ofMillis(900), Duration.ofMillis(50), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
        CountDownLatch renewals = new CountDownLatch(4), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(shortLease, TestDatabase.newUrl(), name -> {
            if (name.equals("renewClaim")) {
                renewals.countDown();
            }
        });
            ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Clock real = Clock.systemUTC();
            plan(f.store, real, List.of(candidate("a", "1")));
            f.gateway.behavior = (op, e) -> {
                assertTrue(release.await(8, TimeUnit.SECONDS));
                return RemoteResult.success(1, "v3");
            };
            FanOutWorker worker = new FanOutWorker(f.store, f.store, f.gateway, shortLease, real, new ScheduledLeaseHeartbeat(f.store, f.store, shortLease, real, f.heartbeats, shortLease.lease().dividedBy(3), Duration.ofSeconds(5)), () -> 0);
            Future<Boolean> running = executor.submit(worker::tick);
            try {
                assertTrue(renewals.await(6, TimeUnit.SECONDS));
                assertTrue(f.store.claim(f.store.tasks(10).getFirst().id(), real.instant(), shortLease.lease()).isEmpty());
                assertEquals(1, f.store.activeCalls());
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            assertEquals(State.DONE, f.store.tasks(10).getFirst().state());
        }
    }

    @Test
    void incompatibleReplicaPolicyIsRejected() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> new PersistenceTestContext(f.url, "Canada", f.policy));
            assertThrows(IllegalStateException.class, () -> new PersistenceTestContext(f.url, "EU",
                    new Policy(1, 1, Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(1),
                            Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10))));
        }
    }

    @Test
    void jitterIsBoundedAndCapped() {
        Policy p = policy();
        assertEquals(Duration.ofSeconds(1), p.retryDelay(1, () -> 0));
        assertTrue(p.retryDelay(2, () -> .999).toMillis() < 4000);
        assertEquals(Duration.ofSeconds(8), p.retryDelay(30, () -> 0));
    }
}
