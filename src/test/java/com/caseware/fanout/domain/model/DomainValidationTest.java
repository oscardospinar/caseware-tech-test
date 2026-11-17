package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.exception.DomainErrorCode;
import com.caseware.fanout.domain.exception.InvalidIdentifierException;
import com.caseware.fanout.domain.exception.InvalidRevisionException;
import com.caseware.fanout.domain.exception.MissingCandidateEngagementException;
import com.caseware.fanout.domain.exception.InvalidPageCandidatesException;
import com.caseware.fanout.domain.exception.MissingRemoteStateException;
import com.caseware.fanout.domain.exception.MissingRemoteMetadataException;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DomainValidationTest {
    @Test
    void identifierValidationIsSemanticAndDoesNotEchoTheRejectedValue() {
        String invalid = "sensitive-identifier".repeat(20);
        InvalidIdentifierException failure = assertThrows(InvalidIdentifierException.class,
                () -> new Engagement("EU", "firm", invalid));
        assertEquals(DomainErrorCode.INVALID_IDENTIFIER, failure.code());
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void negativeRevisionsHaveTheSameSpecificTypeAcrossSnapshots() {
        assertEquals(DomainErrorCode.INVALID_REVISION,
                assertThrows(InvalidRevisionException.class, () -> new Metadata(-1, "v1")).code());
        assertEquals(DomainErrorCode.INVALID_REVISION,
                assertThrows(InvalidRevisionException.class,
                        () -> new Candidate(new Engagement("EU", "firm", "file"), -1, true)).code());
    }

    @Test
    void candidateWithoutEngagementHasItsOwnFailureType() {
        assertEquals(DomainErrorCode.INVALID_CANDIDATE,
                assertThrows(MissingCandidateEngagementException.class, () -> new Candidate(null, 1, true)).code());
    }

    @Test
    void missingRemoteStateIsDistinctFromMissingMetadata() {
        assertEquals(DomainErrorCode.INVALID_REMOTE_RESULT,
                assertThrows(MissingRemoteStateException.class,
                        () -> new RemoteResult(null, null, null, null)).code());
    }

    @Test
    void successfulResultRequiresMetadata() {
        assertEquals(DomainErrorCode.INVALID_REMOTE_RESULT,
                assertThrows(MissingRemoteMetadataException.class,
                        () -> new RemoteResult(RemoteState.SUCCEEDED, null, null, null)).code());
    }

    @Test
    void pageRejectsMissingCandidateListAndNullEntries() {
        assertEquals(DomainErrorCode.INVALID_PAGE,
                assertThrows(InvalidPageCandidatesException.class, () -> new Page(null, null)).code());
        assertEquals(DomainErrorCode.INVALID_PAGE,
                assertThrows(InvalidPageCandidatesException.class,
                        () -> new Page(Arrays.asList((Candidate) null), null)).code());
    }
}
