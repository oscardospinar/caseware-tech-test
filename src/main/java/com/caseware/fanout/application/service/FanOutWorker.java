package com.caseware.fanout.application.service;

import com.caseware.fanout.domain.policy.Policy;
import java.time.Clock;
import java.time.Instant;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.Callable;
import com.caseware.fanout.application.port.in.ProcessNextTask;
import java.util.function.DoubleSupplier;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.RemoteState;
import com.caseware.fanout.domain.model.Task;
import com.caseware.fanout.application.port.out.EngagementGateway;
import com.caseware.fanout.application.port.out.JobStore;
import com.caseware.fanout.application.port.out.LeaseHeartbeat;
import com.caseware.fanout.application.port.out.MessageQueue;
import java.util.Optional;
import com.caseware.fanout.application.port.out.LeaseHeartbeat.Registration;

/**
 * One delivery per tick; local concurrency is bounded by the caller's fixed worker threads.
 */
public final class FanOutWorker implements ProcessNextTask {

    private static final Logger LOGGER = System.getLogger(FanOutWorker.class.getName());

    private final JobStore store;

    private final MessageQueue queue;

    private final EngagementGateway gateway;

    private final Policy policy;

    private final Clock clock;

    private final LeaseHeartbeat heartbeats;

    private final DoubleSupplier random;

    public FanOutWorker(JobStore store, MessageQueue queue, EngagementGateway gateway, Policy policy, Clock clock, LeaseHeartbeat heartbeats, DoubleSupplier random) {
        this.store = store;
        this.queue = queue;
        this.gateway = gateway;
        this.policy = policy;
        this.clock = clock;
        this.heartbeats = heartbeats;
        this.random = random;
    }

    @Override
    public boolean tick() {
        Optional<Delivery> message = queue.receive(clock.instant(), policy.lease());
        if (message.isEmpty()) {
            return false;
        }
        processDelivery(message.get());
        return true;
    }

    private void processDelivery(Delivery delivery) {
        Task task = store.task(delivery.taskId());
        if (task.terminal()) {
            queue.acknowledge(delivery);
            return;
        }
        Optional<Claim> claimed = store.claim(task.id(), clock.instant(), policy.lease());
        if (claimed.isEmpty()) {
            queue.defer(delivery, later(task.availableAt(), clock.instant().plus(policy.pollDelay())));
            return;
        }
        processClaim(claimed.get(), delivery);
    }

    private void processClaim(Claim claim, Delivery delivery) {
        if (claim.task().operationId() == null && !store.targetActive(claim.task().publication())) {
            if (store.supersede(claim, clock.instant())) {
                queue.acknowledge(delivery);
            }
            return;
        }
        Optional<Claim> admitted = store.admit(claim, clock.instant());
        if (admitted.isEmpty()) {
            defer(claim, delivery, "ADMISSION_DEFERRED");
            return;
        }
        Claim active = admitted.get();
        try (Registration renewal = heartbeats.start(active, delivery)) {
            reconcileOperation(active, delivery).ifPresent(result -> handleResult(active, delivery, result));
        }
    }

    private Optional<RemoteResult> reconcileOperation(Claim claim, Delivery delivery) {
        Optional<RemoteResult> result = invoke(() -> gateway.lookup(claim.task().operationId()), claim, delivery);
        if (result.isEmpty() || result.get().state() != RemoteState.NOT_FOUND) {
            return result;
        }
        if (!store.authorizeSubmission(claim, clock.instant())) {
            defer(claim, delivery, "SUBMISSION_DEFERRED");
            return Optional.empty();
        }
        return invoke(() -> gateway.execute(claim.task().operationId(), claim.task().engagement()), claim, delivery);
    }

    private void handleResult(Claim claim, Delivery delivery, RemoteResult result) {
        if (result.state() == RemoteState.RUNNING || result.state() == RemoteState.NOT_FOUND) {
            defer(claim, delivery, "REMOTE_PENDING");
            return;
        }
        Instant retryAt = clock.instant().plus(policy.retryDelay(claim.task().attempts(), random));
        if (store.complete(claim, result, clock.instant(), retryAt)) {
            settleDelivery(delivery);
        }
    }

    private void settleDelivery(Delivery delivery) {
        Task updated = store.task(delivery.taskId());
        if (updated.terminal()) {
            queue.acknowledge(delivery);
        } else {
            queue.defer(delivery, updated.availableAt());
        }
    }

    private Optional<RemoteResult> invoke(Callable<RemoteResult> call, Claim claim, Delivery delivery) {
        try {
            return Optional.of(Objects.requireNonNull(call.call(), "Gateway returned null"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.INFO, "Gateway call interrupted; remote outcome remains uncertain. Reservation retained.");
        } catch (Exception e) {
            // Exception messages and causes may contain tenant data; log only the failure type.
            LOGGER.log(Level.WARNING, "Gateway call failed; remote outcome remains uncertain. Type: {0}",
                    e.getClass().getSimpleName());
        }
        // Storage failures propagate; they must not be misclassified as dependency failures.
        defer(claim, delivery, "REMOTE_UNCERTAIN");
        return Optional.empty();
    }

    private void defer(Claim claim, Delivery delivery, String code) {
        Instant now = clock.instant(), due = now.plus(policy.pollDelay());
        if (store.defer(claim, now, due, code)) {
            queue.defer(delivery, due);
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
