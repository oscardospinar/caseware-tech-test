package com.caseware.fanout.domain.exception;

public final class InvalidPageCandidatesException extends DomainException {
    public InvalidPageCandidatesException() {
        super(DomainErrorCode.INVALID_PAGE, "Page requires non-null candidates");
    }
}
