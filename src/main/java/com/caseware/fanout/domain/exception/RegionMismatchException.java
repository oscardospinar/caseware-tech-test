package com.caseware.fanout.domain.exception;

public final class RegionMismatchException extends DomainException {
    public RegionMismatchException() {
        super(DomainErrorCode.REGION_MISMATCH, "Operation crosses the configured regional boundary");
    }
}
