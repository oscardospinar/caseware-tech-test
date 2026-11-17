package com.caseware.fanout.domain.exception;

public final class InvalidPolicyTimingException extends DomainException {
    public InvalidPolicyTimingException() {
        super(DomainErrorCode.INVALID_POLICY, "Invalid timing policy");
    }
}
