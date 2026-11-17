package com.caseware.fanout.domain.exception;

public final class PublicationConflictException extends DomainException {
    public PublicationConflictException() {
        super(DomainErrorCode.PUBLICATION_CONFLICT, "Publication identity was reused with a different payload");
    }
}
