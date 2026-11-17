package com.caseware.fanout.domain.exception;

/** Business rule failure, independent of Spring and persistence. */
public abstract class DomainException extends RuntimeException {
    private final DomainErrorCode code;

    protected DomainException(DomainErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public final DomainErrorCode code() {
        return code;
    }
}
