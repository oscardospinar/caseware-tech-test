package com.caseware.fanout.application.exception;

/** A storage failure, not evidence that a remote operation failed or stopped. */
public final class PersistenceOperationException extends ApplicationException {
    private final String operation;

    public PersistenceOperationException(String operation, Throwable cause) {
        super(ApplicationErrorCode.PERSISTENCE_FAILURE, "Persistence operation failed: " + operation, cause);
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
