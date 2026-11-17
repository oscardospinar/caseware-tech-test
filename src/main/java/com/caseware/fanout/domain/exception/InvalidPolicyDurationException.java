package com.caseware.fanout.domain.exception;

public final class InvalidPolicyDurationException extends DomainException {
    public InvalidPolicyDurationException() {
        super(DomainErrorCode.INVALID_POLICY, "Durations must be positive milliseconds");
    }
}
