package com.caseware.fanout.domain.policy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.caseware.fanout.domain.exception.InvalidTaskStateException;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.RemoteState;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import static org.junit.jupiter.api.Assertions.*;
import com.caseware.fanout.domain.policy.TaskCompletionPolicy.Decision;

class TaskCompletionPolicyTest {

    private static final Instant RETRY = Instant.parse("2026-11-16T12:00:00Z");

    private Task task(int attempts) {
        return new Task("task", new Publication("p", "EU", "audit", "v7"), new Engagement("EU", "firm", "file"), 2, State.RUNNING, attempts, "op", RETRY, null);
    }

    @Test
    void staleResultsCannotRegressVerifiedMetadata() {
        for (Metadata current : new Metadata[] { null, new Metadata(3, "v3") }) {
            Decision decision = TaskCompletionPolicy.decide(task(1), RemoteResult.success(1, "v1"), current, true, 3, RETRY);
            assertEquals(State.SUPERSEDED, decision.state());
            assertEquals("STALE_RESULT", decision.errorCode());
            assertNull(decision.projection());
        }
        Decision newerProjection = TaskCompletionPolicy.decide(task(1), RemoteResult.success(2, "v2"), new Metadata(3, "v3"), true, 3, RETRY);
        assertNull(newerProjection.projection());
        assertEquals(State.SUPERSEDED, newerProjection.state());
    }

    @Test
    void equalRevisionIsIdempotentButConflictingVersionFails() {
        Decision same = TaskCompletionPolicy.decide(task(1), RemoteResult.success(2, "v2"), new Metadata(2, "v2"), true, 3, RETRY);
        assertEquals(State.DONE, same.state());
        Decision conflict = TaskCompletionPolicy.decide(task(1), RemoteResult.success(2, "other"), new Metadata(2, "v2"), true, 3, RETRY);
        assertEquals(State.FAILED, conflict.state());
        assertEquals("REVISION_CONFLICT", conflict.errorCode());
        assertNull(conflict.projection());
    }

    @Test
    void retryHonorsRemoteDelayAndStopsAtAttemptBudget() {
        RemoteResult result = new RemoteResult(RemoteState.RETRYABLE_FAILURE, null, "BUSY", RETRY.plusSeconds(60));
        Decision retry = TaskCompletionPolicy.decide(task(2), result, null, true, 3, RETRY);
        assertEquals(State.READY, retry.state());
        assertEquals(RETRY.plusSeconds(60), retry.dueAt());
        assertEquals(State.FAILED, TaskCompletionPolicy.decide(task(3), result, null, true, 3, RETRY).state());
        RemoteResult earlier = new RemoteResult(RemoteState.RETRYABLE_FAILURE, null, "BUSY", RETRY.minusSeconds(1));
        assertEquals(RETRY, TaskCompletionPolicy.decide(task(1), earlier, null, true, 3, RETRY).dueAt());
    }

    @Test
    void withdrawalKeepsVerifiedMetadataButSupersedesEveryTerminalOutcome() {
        Decision success = TaskCompletionPolicy.decide(task(1), RemoteResult.success(2, "v2"), null, false, 3, RETRY);
        assertEquals(State.SUPERSEDED, success.state());
        assertEquals(new Metadata(2, "v2"), success.projection());
        for (RemoteResult failure : new RemoteResult[] { RemoteResult.retryable("BUSY"), RemoteResult.permanent("INVALID") }) {
            Decision decision = TaskCompletionPolicy.decide(task(1), failure, null, false, 3, RETRY);
            assertEquals(State.SUPERSEDED, decision.state());
            assertNull(decision.projection());
        }
    }

    @Test
    void unresolvedRemoteOperationCannotBeFinalized() {
        for (RemoteResult unresolved : new RemoteResult[] { RemoteResult.running(), RemoteResult.notFound() }) {
            assertThrows(InvalidTaskStateException.class, () -> TaskCompletionPolicy.decide(task(1), unresolved, null, true, 3, RETRY));
        }
    }
}
