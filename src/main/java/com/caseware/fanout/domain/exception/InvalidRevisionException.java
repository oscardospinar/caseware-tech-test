package com.caseware.fanout.domain.exception;

public final class InvalidRevisionException extends DomainException {
    public InvalidRevisionException() {
        super(DomainErrorCode.INVALID_REVISION, "Revision must be nonnegative");
    }
}
