package com.caseware.fanout.domain.exception;

public final class MissingRemoteStateException extends DomainException {
    public MissingRemoteStateException() {
        super(DomainErrorCode.INVALID_REMOTE_RESULT, "Remote result requires a state");
    }
}
