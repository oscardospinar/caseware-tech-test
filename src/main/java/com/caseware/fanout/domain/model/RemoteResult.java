package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.exception.MissingRemoteStateException;
import com.caseware.fanout.domain.exception.MissingRemoteMetadataException;
import java.time.Instant;

/**
 * Remote observation. RUNNING includes accepted operations with no known terminal outcome.
 */
public record RemoteResult(RemoteState state, Metadata metadata, String errorCode, Instant retryAfter) {

    public RemoteResult {
        if (state == null) {
            throw new MissingRemoteStateException();
        }
        if (state == RemoteState.SUCCEEDED && metadata == null) {
            throw new MissingRemoteMetadataException();
        }
    }

    public static RemoteResult success(long revision, String version) {
        return new RemoteResult(RemoteState.SUCCEEDED, new Metadata(revision, version), null, null);
    }

    public static RemoteResult running() {
        return new RemoteResult(RemoteState.RUNNING, null, null, null);
    }

    public static RemoteResult notFound() {
        return new RemoteResult(RemoteState.NOT_FOUND, null, null, null);
    }

    public static RemoteResult retryable(String code) {
        return new RemoteResult(RemoteState.RETRYABLE_FAILURE, null, code, null);
    }

    public static RemoteResult permanent(String code) {
        return new RemoteResult(RemoteState.PERMANENT_FAILURE, null, code, null);
    }
}
