package com.caseware.fanout.domain.model;

import java.time.Instant;

/** Immutable task snapshot. State transitions are persisted atomically through the job-store port. */
public record Task(String id, Publication publication, Engagement engagement, long requiredRevision,
                   State state, int attempts, String operationId, Instant availableAt, String errorCode) {
    public boolean terminal() {
        return state == State.DONE || state == State.FAILED || state == State.SUPERSEDED;
    }
}
