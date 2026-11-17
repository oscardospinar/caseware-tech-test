package com.caseware.fanout.domain.policy;

import com.caseware.fanout.domain.exception.InvalidTaskStateException;
import java.time.Instant;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;

/**
 * Pure completion rules. The adapter reads the snapshot and applies this decision atomically.
 */
public final class TaskCompletionPolicy {

    private TaskCompletionPolicy() {
    }

    public record Decision(State state, Instant dueAt, String errorCode, Metadata projection) {
    }

    public static Decision decide(Task task, RemoteResult result, Metadata current, boolean targetActive, int maxAttempts, Instant retryAt) {
        Decision decision = switch (result.state()) {
            case RUNNING, NOT_FOUND -> throw InvalidTaskStateException.nonTerminalResult();
            case SUCCEEDED -> successfulResult(task, result, current, retryAt);
            case RETRYABLE_FAILURE -> retryableFailure(task, result, maxAttempts, retryAt);
            case PERMANENT_FAILURE -> new Decision(State.FAILED, retryAt, result.state().name(), null);
        };
        // Verified metadata is still useful after withdrawal; the job itself must not remain actionable.
        if (!targetActive) {
            return new Decision(State.SUPERSEDED, decision.dueAt(), decision.errorCode(), decision.projection());
        }
        return decision;
    }

    private static Decision successfulResult(Task task, RemoteResult result, Metadata current, Instant retryAt) {
        Metadata observed = result.metadata();
        if (observed.revision() < task.requiredRevision() || (current != null && current.revision() > observed.revision())) {
            return new Decision(State.SUPERSEDED, retryAt, "STALE_RESULT", null);
        }
        if (current != null && current.revision() == observed.revision()
                && !current.appliedVersion().equals(observed.appliedVersion())) {
            return new Decision(State.FAILED, retryAt, "REVISION_CONFLICT", null);
        }
        return new Decision(State.DONE, retryAt, result.state().name(), observed);
    }

    private static Decision retryableFailure(Task task, RemoteResult result, int maxAttempts, Instant retryAt) {
        State next = task.attempts() < maxAttempts ? State.READY : State.FAILED;
        Instant due = retryAt;
        if (result.retryAfter() != null && result.retryAfter().isAfter(due)) {
            due = result.retryAfter();
        }
        return new Decision(next, due, result.state().name(), null);
    }
}
