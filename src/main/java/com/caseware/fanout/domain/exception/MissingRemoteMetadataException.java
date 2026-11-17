package com.caseware.fanout.domain.exception;

public final class MissingRemoteMetadataException extends DomainException {
    public MissingRemoteMetadataException() {
        super(DomainErrorCode.INVALID_REMOTE_RESULT, "Successful remote result requires metadata");
    }
}
