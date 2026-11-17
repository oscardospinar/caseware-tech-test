package com.caseware.fanout.bootstrap;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.domain.policy.Policy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational defaults live in application YAML; Java validates their contracts. */
@ConfigurationProperties("fanout")
public record FanOutProperties(String region, int localConcurrency, int pageSize, boolean workerEnabled,
        Limits limits, Runtime runtime, Heartbeat heartbeat, Database database) {

    public FanOutProperties {
        if (region == null || region.isBlank() || region.length() > 200) {
            throw new InvalidConfigurationException("fanout.region must be a nonblank identifier");
        }
        bounded(localConcurrency, 128, "fanout.local-concurrency");
        bounded(pageSize, 1000, "fanout.page-size");
        if (limits == null || runtime == null || heartbeat == null || database == null) {
            throw new InvalidConfigurationException("fanout limits, runtime, heartbeat and database settings are required");
        }
        if (limits.lease() != null && heartbeat.interval().compareTo(limits.lease().dividedBy(2)) > 0) {
            throw new InvalidConfigurationException("fanout.heartbeat.interval must not exceed half the lease");
        }
    }

    private static void bounded(int value, int maximum, String property) {
        if (value < 1 || value > maximum) {
            throw new InvalidConfigurationException(property + " is outside its supported range");
        }
    }

    private static void positive(Duration value, String property) {
        if (value == null || value.toMillis() < 1) {
            throw new InvalidConfigurationException(property + " must be at least one millisecond");
        }
    }

    public record Runtime(int dispatchBatchSize, Duration idleDelay, Duration failureDelay, Duration shutdownTimeout) {
        public Runtime {
            bounded(dispatchBatchSize, 1000, "fanout.runtime.dispatch-batch-size");
            positive(idleDelay, "fanout.runtime.idle-delay");
            positive(failureDelay, "fanout.runtime.failure-delay");
            positive(shutdownTimeout, "fanout.runtime.shutdown-timeout");
        }
    }

    public record Heartbeat(int threads, Duration interval, Duration shutdownTimeout) {
        public Heartbeat {
            bounded(threads, 128, "fanout.heartbeat.threads");
            positive(interval, "fanout.heartbeat.interval");
            positive(shutdownTimeout, "fanout.heartbeat.shutdown-timeout");
        }
    }

    public record Database(Duration lockTimeout) {
        public Database {
            positive(lockTimeout, "fanout.database.lock-timeout");
            if (lockTimeout.toMillis() > Integer.MAX_VALUE) {
                throw new InvalidConfigurationException("fanout.database.lock-timeout exceeds PostgreSQL's supported range");
            }
        }
    }

    public record Limits(int maxActive, int maxPerFirm, Duration minStartSpacing, int maxAttempts,
            Duration lease, Duration pollDelay, Duration baseBackoff, Duration maxBackoff,
            int circuitFailures, Duration circuitCooldown) {
        public Policy toPolicy() {
            return new Policy(maxActive, maxPerFirm, minStartSpacing, maxAttempts, lease, pollDelay,
                    baseBackoff, maxBackoff, circuitFailures, circuitCooldown);
        }
    }
}
