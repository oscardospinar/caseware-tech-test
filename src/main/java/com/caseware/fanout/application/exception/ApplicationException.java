package com.caseware.fanout.application.exception;

/** Failure exposed by an application operation or an outgoing port. */
public abstract class ApplicationException extends RuntimeException {
    private final ApplicationErrorCode code;

    protected ApplicationException(ApplicationErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public final ApplicationErrorCode code() {
        return code;
    }
}
