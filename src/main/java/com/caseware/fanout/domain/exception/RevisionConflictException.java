package com.caseware.fanout.domain.exception;

public final class RevisionConflictException extends DomainException {
    public RevisionConflictException() {
        super(DomainErrorCode.REVISION_CONFLICT, "The same source revision has conflicting metadata");
    }
}
