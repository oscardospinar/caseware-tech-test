package com.caseware.fanout.domain.exception;

public final class MissingCandidateEngagementException extends DomainException {
    public MissingCandidateEngagementException() {
        super(DomainErrorCode.INVALID_CANDIDATE, "Candidate requires an engagement");
    }
}
