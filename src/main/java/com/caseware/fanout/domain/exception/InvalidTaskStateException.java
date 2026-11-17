package com.caseware.fanout.domain.exception;

public final class InvalidTaskStateException extends DomainException {
    private InvalidTaskStateException(DomainErrorCode code, String message) {
        super(code, message);
    }

    public static InvalidTaskStateException nonTerminalResult() {
        return new InvalidTaskStateException(DomainErrorCode.NON_TERMINAL_RESULT,
                "An unresolved remote operation cannot be finalized");
    }

    public static InvalidTaskStateException missingOperation() {
        return new InvalidTaskStateException(DomainErrorCode.MISSING_REMOTE_OPERATION,
                "Task completion requires a reserved remote operation");
    }
}
