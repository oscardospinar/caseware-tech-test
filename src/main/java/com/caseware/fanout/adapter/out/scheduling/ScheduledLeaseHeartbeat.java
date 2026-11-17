package com.caseware.fanout.adapter.out.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.caseware.fanout.application.port.out.JobStore;
import com.caseware.fanout.application.port.out.MessageQueue;
import com.caseware.fanout.application.port.out.LeaseHeartbeat;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.policy.Policy;
import java.util.concurrent.ScheduledFuture;

/**
 * Owns the scheduler; closing a registration cancels only that renewal.
 */
public final class ScheduledLeaseHeartbeat implements LeaseHeartbeat, AutoCloseable {

    private static final Logger LOGGER = System.getLogger(ScheduledLeaseHeartbeat.class.getName());

    private final JobStore store;

    private final MessageQueue queue;

    private final Policy policy;

    private final Clock clock;

    private final ScheduledExecutorService scheduler;

    private final Duration interval;
    private final Duration shutdownTimeout;

    public ScheduledLeaseHeartbeat(JobStore store, MessageQueue queue, Policy policy, Clock clock,
            ScheduledExecutorService scheduler, Duration interval, Duration shutdownTimeout) {
        this.store = store;
        this.queue = queue;
        this.policy = policy;
        this.clock = clock;
        this.scheduler = scheduler;
        this.interval = interval;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public Registration start(Claim claim, Delivery delivery) {
        long period = interval.toMillis();
        ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(
                () -> renewLease(claim, delivery), period, period, TimeUnit.MILLISECONDS);
        return () -> renewal.cancel(false);
    }

    private void renewLease(Claim claim, Delivery delivery) {
        try {
            if (store.renew(claim, clock.instant(), policy.lease())) {
                queue.renew(delivery, clock.instant(), policy.lease());
            }
        } catch (RuntimeException e) {
            // Keep scheduling renewals. Fencing protects late writes; expiry never frees remote capacity.
            LOGGER.log(Level.WARNING, "Lease renewal failed; remote reservation retained. Type: {0}",
                    e.getClass().getSimpleName());
        }
    }

    public boolean isTerminated() {
        return scheduler.isTerminated();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.INFO, "Heartbeat shutdown wait interrupted; shutdown remains requested.");
        }
    }
}
