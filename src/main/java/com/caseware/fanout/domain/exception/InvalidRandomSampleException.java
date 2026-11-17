package com.caseware.fanout.domain.exception;

public final class InvalidRandomSampleException extends DomainException {
    public InvalidRandomSampleException() {
        super(DomainErrorCode.INVALID_RANDOM_SAMPLE, "Random sample outside [0,1)");
    }
}
