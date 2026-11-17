package com.caseware.fanout.domain.exception;

/** Stable, non-sensitive classification; never includes tenant identifiers or supplied values. */
public enum DomainErrorCode {
    INVALID_IDENTIFIER, INVALID_REVISION, INVALID_CANDIDATE, INVALID_PAGE,
    INVALID_REMOTE_RESULT, INVALID_POLICY, INVALID_RANDOM_SAMPLE,
    REGION_MISMATCH, PUBLICATION_CONFLICT, REVISION_CONFLICT,
    NON_TERMINAL_RESULT, MISSING_REMOTE_OPERATION
}
