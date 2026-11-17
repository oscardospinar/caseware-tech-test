package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.domain.policy.Policy;
import com.caseware.fanout.application.service.*;
import com.caseware.fanout.application.port.out.*;
import com.caseware.fanout.adapter.out.persistence.SpringDataStore;
import com.caseware.fanout.adapter.out.scheduling.ScheduledLeaseHeartbeat;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.RemoteState;

final class TestSupport {

    static final Instant START = Instant.parse("2026-11-16T12:00:00Z");

    static final Publication PUB = new Publication("publication-1", "EU", "audit", "v7");

    static Policy policy() {
        return new Policy(2, 1, Duration.ZERO, 3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
    }

    static Candidate candidate(String firm, String id) {
        return new Candidate(new Engagement("EU", firm, id), 1, true);
    }

    static Inventory inventory(List<Candidate> files) {
        return (p, cursor, limit) -> {
            int start = cursor == null ? 0 : Integer.parseInt(cursor), end = Math.min(files.size(), start + limit);
            return new Page(files.subList(start, end), end == files.size() ? null : Integer.toString(end));
        };
    }

    static void plan(SpringDataStore store, Clock clock, List<Candidate> files) {
        FanOutPlanner planner = new FanOutPlanner(store, inventory(files), clock, 2);
        while (planner.step(PUB)) {
        }
        new OutboxDispatcher(store, store, clock).dispatch(1000);
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> time = new AtomicReference<>(START);

        void advance(Duration d) {
            time.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException();
            }
            return this;
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }

    interface Execution {

        RemoteResult run(String operation, Engagement engagement) throws Exception;
    }

    static final class FakeGateway implements EngagementGateway {

        final Map<String, RemoteResult> outcomes = new ConcurrentHashMap<>();

        final List<String> started = new CopyOnWriteArrayList<>();

        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), invocations = new AtomicInteger();

        final Map<String, AtomicInteger> perFirm = new ConcurrentHashMap<>();

        final AtomicInteger firmPeak = new AtomicInteger();

        volatile Execution behavior = (op, e) -> RemoteResult.success(1, "v3");

        @Override
        public RemoteResult lookup(String op) {
            return outcomes.getOrDefault(op, RemoteResult.notFound());
        }

        @Override
        public RemoteResult execute(String op, Engagement e) throws Exception {
            invocations.incrementAndGet();
            RemoteResult previous = outcomes.putIfAbsent(op, RemoteResult.running());
            if (previous != null) {
                return previous;
            }
            started.add(op);
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            AtomicInteger firm = perFirm.computeIfAbsent(e.firm(), ignored -> new AtomicInteger());
            firmPeak.accumulateAndGet(firm.incrementAndGet(), Math::max);
            RemoteResult result = behavior.run(op, e);
            outcomes.put(op, result);
            if (result.state() != RemoteState.RUNNING) {
                active.decrementAndGet();
                firm.decrementAndGet();
            }
            return result;
        }

        void finish(String op, RemoteResult result) {
            outcomes.put(op, result);
            active.decrementAndGet();
        }
    }

    static final class Fixture implements AutoCloseable {

        final MutableClock clock = new MutableClock();

        final Policy policy;

        final String url;

        final SpringDataStore store;

        final PersistenceTestContext database;

        final FakeGateway gateway = new FakeGateway();

        final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2);

        Fixture() {
            this(policy());
        }

        Fixture(Policy policy) {
            this(policy, TestDatabase.newUrl(), ignored -> {
            });
        }

        Fixture(Policy policy, String url, Consumer<String> beforeCommit) {
            this.policy = policy;
            this.url = url;
            database = new PersistenceTestContext(url, "EU", policy, beforeCommit);
            store = database.store();
        }

        FanOutWorker worker() {
            return worker(store);
        }

        FanOutWorker worker(SpringDataStore repo) {
            return new FanOutWorker(repo, repo, gateway, policy, clock, new ScheduledLeaseHeartbeat(repo, repo, policy, clock, heartbeats, policy.lease().dividedBy(3), Duration.ofSeconds(5)), () -> 0.0);
        }

        @Override
        public void close() {
            heartbeats.shutdownNow();
            database.close();
        }
    }
}
