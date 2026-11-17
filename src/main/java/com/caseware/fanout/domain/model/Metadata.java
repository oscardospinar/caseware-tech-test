package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.exception.InvalidRevisionException;
import com.caseware.fanout.domain.validation.IdentifierValidation;

/**
 * Verified source revision and applied template version.
 */
public record Metadata(long revision, String appliedVersion) {

    public Metadata {
        if (revision < 0) {
            throw new InvalidRevisionException();
        }
        IdentifierValidation.required(appliedVersion);
    }
}
