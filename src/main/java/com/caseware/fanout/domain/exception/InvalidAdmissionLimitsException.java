package com.caseware.fanout.domain.exception;

public final class InvalidAdmissionLimitsException extends DomainException {
    public InvalidAdmissionLimitsException() {
        super(DomainErrorCode.INVALID_POLICY, "Invalid admission limits");
    }
}
