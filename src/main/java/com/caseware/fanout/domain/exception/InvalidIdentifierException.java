package com.caseware.fanout.domain.exception;

public final class InvalidIdentifierException extends DomainException {
    public InvalidIdentifierException() {
        super(DomainErrorCode.INVALID_IDENTIFIER, "Identifier must be nonblank and at most 200 characters");
    }
}
