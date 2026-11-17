package com.caseware.fanout.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.Run;
import com.caseware.fanout.domain.model.Task;

public interface JobStore {
    Run start(Publication publication);
    Run run(Publication publication);
    /** Atomic compare-and-set of cursor + unique task inserts + outbox; false if another planner won. */
    boolean savePage(Run expected, Page page, Instant now);
    List<String> unpublished(int limit);
    void published(String taskId);
    Optional<Claim> claim(String taskId, Instant now, Duration lease);
    boolean renew(Claim claim, Instant now, Duration lease);
    Task task(String taskId);
    /** Atomically reserves durable capacity and assigns an operation ID; existing operations keep their reservation. */
    Optional<Claim> admit(Claim claim, Instant now);
    /** Every outbound execute request, including same-ID recovery, passes the shared start-rate gate. */
    boolean authorizeSubmission(Claim claim, Instant now);
    boolean defer(Claim claim, Instant now, Instant dueAt, String code);
    boolean complete(Claim claim, RemoteResult result, Instant now, Instant retryAt);
    /** Supersede only before starting an operation; existing remote work must first be reconciled. */
    boolean supersede(Claim claim, Instant now);
    boolean targetActive(Publication publication);
    /** Bounded operational inspection, not the product query API. */
    List<Task> tasks(int limit);
    long activeCalls();
}
