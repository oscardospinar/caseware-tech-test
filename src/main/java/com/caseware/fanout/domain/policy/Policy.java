package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.exception.InvalidAdmissionLimitsException;
import com.caseware.fanout.domain.exception.InvalidPolicyDurationException;
import com.caseware.fanout.domain.exception.InvalidPolicyTimingException;
import com.caseware.fanout.domain.exception.InvalidRandomSampleException;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Configuration is persisted/checked at the shared dependency boundary by the JDBC adapter.
 */
public record Policy(int maxActive, int maxPerFirm, Duration minStartSpacing, int maxAttempts, Duration lease, Duration pollDelay, Duration baseBackoff, Duration maxBackoff, int circuitFailures, Duration circuitCooldown) {

    public Policy {
        validateAdmissionLimits(maxActive, maxPerFirm, maxAttempts, circuitFailures);
        validatePositiveDurations(lease, pollDelay, baseBackoff, maxBackoff, circuitCooldown);
        validateTiming(minStartSpacing, baseBackoff, maxBackoff);
    }

    private static void validateAdmissionLimits(int maxActive, int maxPerFirm, int maxAttempts, int circuitFailures) {
        if (maxActive < 1 || maxPerFirm < 1 || maxPerFirm > maxActive || maxAttempts < 1 || circuitFailures < 1) {
            throw new InvalidAdmissionLimitsException();
        }
    }

    private static void validatePositiveDurations(Duration... durations) {
        for (Duration duration : durations) {
            if (duration == null || duration.toMillis() < 1) {
                throw new InvalidPolicyDurationException();
            }
        }
    }

    private static void validateTiming(Duration minStartSpacing, Duration baseBackoff, Duration maxBackoff) {
        if (minStartSpacing == null || minStartSpacing.isNegative() || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new InvalidPolicyTimingException();
        }
    }

    public Duration retryDelay(int completedAttempts, DoubleSupplier random) {
        long cap = backoffCap(completedAttempts);
        double sample = random.getAsDouble();
        if (sample < 0 || sample >= 1) {
            throw new InvalidRandomSampleException();
        }
        // Equal jitter avoids synchronized retry bursts and zero-delay busy loops.
        return Duration.ofMillis(Math.max(1, (long) (cap * (0.5 + sample * 0.5))));
    }

    private long backoffCap(int completedAttempts) {
        long cap = baseBackoff.toMillis();
        long maximum = maxBackoff.toMillis();
        for (int i = 1; i < completedAttempts && cap < maximum; i++) {
            cap = cap > maximum / 2 ? maximum : cap * 2;
        }
        return Math.min(cap, maximum);
    }

}
