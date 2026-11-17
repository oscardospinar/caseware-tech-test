package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.exception.DomainErrorCode;
import com.caseware.fanout.domain.exception.InvalidAdmissionLimitsException;
import com.caseware.fanout.domain.exception.InvalidPolicyDurationException;
import com.caseware.fanout.domain.exception.InvalidPolicyTimingException;
import com.caseware.fanout.domain.exception.InvalidRandomSampleException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyValidationTest {

    @Test
    void perFirmLimitCannotExceedGlobalLimit() {
        InvalidAdmissionLimitsException failure = assertThrows(InvalidAdmissionLimitsException.class,
                () -> new Policy(1, 2, Duration.ZERO, 3, Duration.ofSeconds(10), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(15)));
        assertEquals(DomainErrorCode.INVALID_POLICY, failure.code());
    }

    @Test
    void missingOrNonpositiveLeaseHasItsOwnFailureType() {
        for (Duration lease : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            InvalidPolicyDurationException failure = assertThrows(InvalidPolicyDurationException.class,
                    () -> new Policy(2, 1, Duration.ZERO, 3, lease, Duration.ofSeconds(1),
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(15)));
            assertEquals(DomainErrorCode.INVALID_POLICY, failure.code());
        }
    }

    @Test
    void backoffCapCannotBeLowerThanBaseDelay() {
        InvalidPolicyTimingException failure = assertThrows(InvalidPolicyTimingException.class,
                () -> new Policy(2, 1, Duration.ZERO, 3, Duration.ofSeconds(10), Duration.ofSeconds(1),
                        Duration.ofSeconds(30), Duration.ofSeconds(1), 3, Duration.ofSeconds(15)));
        assertEquals(DomainErrorCode.INVALID_POLICY, failure.code());
    }

    @Test
    void outOfRangeRandomSamplesHaveTheirOwnFailureType() {
        Policy policy = new Policy(2, 1, Duration.ZERO, 3, Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(15));
        for (double sample : new double[] {-0.1, 1.0, Double.POSITIVE_INFINITY}) {
            InvalidRandomSampleException failure = assertThrows(InvalidRandomSampleException.class,
                    () -> policy.retryDelay(1, () -> sample));
            assertEquals(DomainErrorCode.INVALID_RANDOM_SAMPLE, failure.code());
        }
    }
}
