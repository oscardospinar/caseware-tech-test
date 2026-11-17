package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.exception.MissingCandidateEngagementException;
import com.caseware.fanout.domain.exception.InvalidRevisionException;

/**
 * Inventory snapshot used to decide whether verification is needed.
 */
public record Candidate(Engagement engagement, long revision, boolean needsVerification) {

    public Candidate {
        if (engagement == null) {
            throw new MissingCandidateEngagementException();
        }
        if (revision < 0) {
            throw new InvalidRevisionException();
        }
    }
}
