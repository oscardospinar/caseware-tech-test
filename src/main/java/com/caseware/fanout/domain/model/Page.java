package com.caseware.fanout.domain.model;

import java.util.List;
import com.caseware.fanout.domain.exception.InvalidPageCandidatesException;

/** Bounded inventory page with a defensive copy of its candidates. */
public record Page(List<Candidate> candidates, String nextCursor) {
    public Page {
        if (candidates == null || candidates.stream().anyMatch(candidate -> candidate == null)) {
            throw new InvalidPageCandidatesException();
        }
        candidates = List.copyOf(candidates);
    }
}
