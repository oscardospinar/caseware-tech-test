package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.adapter.out.persistence.entity.*;
import com.caseware.fanout.adapter.out.persistence.repository.*;
import com.caseware.fanout.application.port.out.JobStore;
import com.caseware.fanout.application.port.out.MessageQueue;
import com.caseware.fanout.domain.policy.Policy;
import com.caseware.fanout.domain.policy.TaskCompletionPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.application.exception.PersistenceOperationException;
import com.caseware.fanout.application.exception.ResourceNotFoundException;
import com.caseware.fanout.application.exception.ResourceNotFoundException.Resource;
import com.caseware.fanout.domain.exception.InvalidTaskStateException;
import com.caseware.fanout.domain.exception.PublicationConflictException;
import com.caseware.fanout.domain.exception.RegionMismatchException;
import com.caseware.fanout.domain.exception.RevisionConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.RemoteState;
import com.caseware.fanout.domain.model.Run;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import com.caseware.fanout.domain.policy.TaskCompletionPolicy.Decision;

/**
 * Atomic port operations backed by Spring Data JDBC. Slow remote calls never enter these transactions.
 */
public final class SpringDataStore implements JobStore, MessageQueue {

    private final ControlRepository control;

    private final RunRepository runs;

    private final TaskRepository tasks;

    private final FirmRepository firms;

    private final OutboxRepository outbox;

    private final MessageRepository messages;

    private final MetadataRepository metadata;

    private final TransactionTemplate transactions;

    private final String region;

    private final Policy policy;

    private final Consumer<String> beforeCommit;

    private final boolean databaseTime;

    public SpringDataStore(ControlRepository control, RunRepository runs, TaskRepository tasks, FirmRepository firms, OutboxRepository outbox, MessageRepository messages, MetadataRepository metadata, PlatformTransactionManager manager, String region, Policy policy) {
        this(control, runs, tasks, firms, outbox, messages, metadata, manager, region, policy, ignored -> {
        }, true);
    }

    // Package-private fault seam: exercise rollback at the actual transaction boundary.
    SpringDataStore(ControlRepository control, RunRepository runs, TaskRepository tasks, FirmRepository firms, OutboxRepository outbox, MessageRepository messages, MetadataRepository metadata, PlatformTransactionManager manager, String region, Policy policy, Consumer<String> beforeCommit) {
        this(control, runs, tasks, firms, outbox, messages, metadata, manager, region, policy, beforeCommit, false);
    }

    private SpringDataStore(ControlRepository control, RunRepository runs, TaskRepository tasks, FirmRepository firms, OutboxRepository outbox, MessageRepository messages, MetadataRepository metadata, PlatformTransactionManager manager, String region, Policy policy, Consumer<String> beforeCommit, boolean databaseTime) {
        this.control = control;
        this.runs = runs;
        this.tasks = tasks;
        this.firms = firms;
        this.outbox = outbox;
        this.messages = messages;
        this.metadata = metadata;
        this.region = Objects.requireNonNull(region);
        this.policy = Objects.requireNonNull(policy);
        this.beforeCommit = beforeCommit;
        this.databaseTime = databaseTime;
        transactions = new TransactionTemplate(manager);
        // Each port call is a durable boundary, even if a caller has an ambient transaction.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        initializeSharedConfiguration();
    }

    private void initializeSharedConfiguration() {
        initializeControlRow();
        tx("initialize", () -> {
            ControlRow row = control.current();
            if (!region.equals(row.region()) || !policy.toString().equals(row.config())) {
                throw new InvalidConfigurationException("Region or shared admission policy mismatch");
            }
            return null;
        });
    }

    private void initializeControlRow() {
        try {
            transactions.execute(status -> control.initialize(region, policy.toString()));
        } catch (DataAccessException | TransactionException e) {
            throw new PersistenceOperationException("initialize", e);
        }
    }

    @Override
    public Run start(Publication p) {
        requireRegion(p.region());
        return tx("start", () -> {
            runs.insert(UUID.randomUUID().toString(), p.id(), p.product(), p.target());
            return toRun(p, requireRun(p));
        });
    }

    @Override
    public Run run(Publication p) {
        return tx("run", () -> toRun(p, requireRun(p)));
    }

    private RunRow readRun(Publication p) {
        requireRegion(p.region());
        RunRow row = runs.byPublication(p.id());
        if (row != null && (!p.product().equals(row.product()) || !p.target().equals(row.target()))) {
            throw new PublicationConflictException();
        }
        return row;
    }

    private RunRow requireRun(Publication publication) {
        RunRow row = readRun(publication);
        if (row == null) {
            throw new ResourceNotFoundException(Resource.PUBLICATION);
        }
        return row;
    }

    private Run toRun(Publication p, RunRow row) {
        return new Run(p, row.cursorValue(), row.finished());
    }

    @Override
    public boolean savePage(Run expected, Page page, Instant now) {
        return tx("savePage", () -> {
            runs.lock(expected.publication().id());
            RunRow current = requireRun(expected.publication());
            if (current.finished() || !Objects.equals(current.cursorValue(), expected.cursor())) {
                return false;
            }
            for (Candidate candidate : page.candidates()) {
                enqueueCandidate(current.id(), candidate, now);
            }
            runs.checkpoint(current.id(), page.nextCursor(), page.nextCursor() == null);
            return true;
        });
    }

    // Called within savePage's transaction: task, notification and cursor commit together.
    private void enqueueCandidate(String runId, Candidate candidate, Instant now) {
        Engagement engagement = candidate.engagement();
        requireRegion(engagement.region());
        if (!candidate.needsVerification()) {
            return;
        }
        if (firms.countFor(engagement.firm()) == 0) {
            firms.insert(engagement.firm());
        }
        if (tasks.existsFor(runId, engagement.firm(), engagement.id()) > 0) {
            return;
        }
        String id = UUID.randomUUID().toString();
        tasks.insert(id, runId, region, engagement.firm(), engagement.id(), candidate.revision(), now.toEpochMilli());
        outbox.insert(id);
    }

    @Override
    public List<String> unpublished(int limit) {
        bounded(limit);
        return tx("outbox", () -> outbox.unpublished(limit));
    }

    @Override
    public void published(String id) {
        tx("published", () -> outbox.published(id));
    }

    @Override
    public void send(String id, Instant now) {
        tx("send", () -> messages.send(id, now.toEpochMilli()));
    }

    @Override
    public Optional<Delivery> receive(Instant now, Duration visibility) {
        return tx("receive", () -> {
            Instant current = currentTime(now);
            MessageRow message = messages.next(current.toEpochMilli());
            if (message == null) {
                return Optional.empty();
            }
            String receipt = UUID.randomUUID().toString();
            firms.turn(tasks.byId(message.taskId()).firm());
            messages.receive(message.id(), receipt, currentTime(current).plus(visibility).toEpochMilli());
            return Optional.of(new Delivery(message.id(), receipt, message.taskId()));
        });
    }

    @Override
    public boolean acknowledge(Delivery d) {
        return tx("ack", () -> messages.acknowledge(d.id(), d.receipt()) == 1);
    }

    @Override
    public boolean defer(Delivery d, Instant due) {
        return tx("deferDelivery", () -> messages.defer(d.id(), d.receipt(), due.toEpochMilli()) == 1);
    }

    @Override
    public boolean renew(Delivery d, Instant now, Duration visibility) {
        return tx("renewDelivery", () -> {
            Instant current = currentTime(now);
            return messages.renew(d.id(), d.receipt(), current.toEpochMilli(), current.plus(visibility).toEpochMilli()) == 1;
        });
    }

    @Override
    public Optional<Claim> claim(String id, Instant now, Duration lease) {
        return tx("claim", () -> {
            tasks.lock(id);
            Instant current = currentTime(now);
            Task task = readTask(id);
            if (task.terminal() || task.availableAt().isAfter(current)) {
                return Optional.empty();
            }
            if (tasks.claim(id, current.toEpochMilli(), current.plus(lease).toEpochMilli()) == 0) {
                return Optional.empty();
            }
            return Optional.of(new Claim(readTask(id), tasks.byId(id).token()));
        });
    }

    @Override
    public boolean renew(Claim claim, Instant now, Duration lease) {
        return tx("renewClaim", () -> {
            if (!owns(claim, now)) {
                return false;
            }
            tasks.renew(claim.task().id(), currentTime(now).plus(lease).toEpochMilli());
            return true;
        });
    }

    @Override
    public Optional<Claim> admit(Claim claim, Instant now) {
        return tx("admit", () -> {
            if (!owns(claim, now)) {
                return Optional.empty();
            }
            Task task = readTask(claim.task().id());
            if (task.operationId() != null) {
                return Optional.of(new Claim(task, claim.token()));
            }
            runs.lockForExecution(task.publication().id());
            control.lock();
            if (!owns(claim, now) || !canStartAttempt(task) || !circuitAllows(task.id(), currentTime(now))) {
                return Optional.empty();
            }
            int attempt = task.attempts() + 1;
            tasks.reserve(task.id(), attempt, task.id() + ":" + attempt);
            return Optional.of(new Claim(readTask(task.id()), claim.token()));
        });
    }

    private boolean canStartAttempt(Task task) {
        return active(task.publication())
                && task.attempts() < policy.maxAttempts()
                && tasks.activeCount() < policy.maxActive()
                && tasks.activeForFirm(task.engagement().firm()) < policy.maxPerFirm();
    }

    @Override
    public boolean authorizeSubmission(Claim claim, Instant now) {
        return tx("authorize", () -> {
            if (!owns(claim, now) || readTask(claim.task().id()).operationId() == null) {
                return false;
            }
            control.lock();
            Instant current = currentTime(now);
            if (!owns(claim, current) || !circuitAllows(claim.task().id(), current) || control.current().nextStart() > current.toEpochMilli()) {
                return false;
            }
            control.nextStart(current.plus(policy.minStartSpacing()).toEpochMilli());
            return true;
        });
    }

    private boolean circuitAllows(String task, Instant now) {
        ControlRow row = control.current();
        if (row.openUntil() > now.toEpochMilli() || (row.probeTask() != null && !row.probeTask().equals(task))) {
            return false;
        }
        if (row.openUntil() != 0 && row.probeTask() == null) {
            control.probe(task);
        }
        return true;
    }

    @Override
    public boolean defer(Claim claim, Instant now, Instant due, String code) {
        return tx("deferTask", () -> {
            if (!owns(claim, now)) {
                return false;
            }
            Task task = readTask(claim.task().id());
            if ("REMOTE_UNCERTAIN".equals(code) && !code.equals(task.errorCode())) {
                control.lock();
                dependencyFailure(task.id(), currentTime(now), false);
            }
            // Keeps the remote operation reservation.
            tasks.defer(task.id(), due.toEpochMilli(), code);
            return true;
        });
    }

    @Override
    public boolean complete(Claim claim, RemoteResult result, Instant now, Instant retryAt) {
        if (result.state() == RemoteState.RUNNING || result.state() == RemoteState.NOT_FOUND) {
            throw InvalidTaskStateException.nonTerminalResult();
        }
        return tx("complete", () -> {
            if (!owns(claim, now)) {
                return false;
            }
            Task task = readTask(claim.task().id());
            if (task.operationId() == null) {
                throw InvalidTaskStateException.missingOperation();
            }
            completeReservedTask(task, result, now, retryAt);
            return true;
        });
    }

    private void completeReservedTask(Task task, RemoteResult result, Instant now, Instant retryAt) {
        runs.lockForExecution(task.publication().id());
        lockProjection(task.engagement());
        Decision decision = TaskCompletionPolicy.decide(task, result, readMetadata(task.engagement()),
                active(task.publication()), policy.maxAttempts(), retryAt);
        if (decision.projection() != null) {
            writeMetadata(task.engagement(), decision.projection());
        }
        recordDependencyOutcome(task.id(), result, now);
        tasks.complete(task.id(), decision.state().name(), decision.dueAt().toEpochMilli(), decision.errorCode());
    }

    private void recordDependencyOutcome(String taskId, RemoteResult result, Instant now) {
        control.lock();
        if (result.state() == RemoteState.RETRYABLE_FAILURE) {
            dependencyFailure(taskId, currentTime(now), true);
        } else {
            healthy(taskId);
        }
    }

    private void dependencyFailure(String task, Instant now, boolean terminal) {
        control.failure();
        ControlRow row = control.current();
        if (row.failures() >= policy.circuitFailures() || row.probeTask() != null) {
            control.openUntil(now.plus(policy.circuitCooldown()).toEpochMilli());
            if (terminal && task.equals(row.probeTask())) {
                control.probe(null);
            }
        }
    }

    private void healthy(String task) {
        ControlRow row = control.current();
        if (task.equals(row.probeTask()) || row.openUntil() == 0) {
            control.healthy();
        }
    }

    @Override
    public boolean supersede(Claim claim, Instant now) {
        return tx("supersede", () -> {
            if (!owns(claim, now) || readTask(claim.task().id()).operationId() != null) {
                return false;
            }
            tasks.supersede(claim.task().id());
            return true;
        });
    }

    private boolean owns(Claim c, Instant now) {
        tasks.lock(c.task().id());
        return tasks.owns(c.task().id(), c.token(), currentTime(now).toEpochMilli()) == 1;
    }

    private boolean active(Publication p) {
        RunRow row = readRun(p);
        return row != null && row.active();
    }

    @Override
    public boolean targetActive(Publication p) {
        return tx("active", () -> active(p));
    }

    public void withdraw(Publication p) {
        tx("withdraw", () -> {
            runs.lock(p.id());
            readRun(p);
            return runs.withdraw(p.id());
        });
    }

    @Override
    public Task task(String id) {
        return tx("task", () -> readTask(id));
    }

    private Task readTask(String id) {
        TaskRow row = tasks.byId(id);
        if (row == null) {
            throw new ResourceNotFoundException(Resource.TASK);
        }
        return toTask(row);
    }

    private Task toTask(TaskRow row) {
        RunRow run = runs.byId(row.runId());
        return new Task(row.id(), new Publication(run.pubId(), region, run.product(), run.target()), new Engagement(region, row.firm(), row.engagement()), row.requiredRevision(), State.valueOf(row.state()), row.attempts(), row.operationId(), Instant.ofEpochMilli(row.dueAt()), row.errorCode());
    }

    public void project(Engagement e, Metadata value) {
        requireRegion(e.region());
        tx("project", () -> {
            lockProjection(e);
            Metadata current = readMetadata(e);
            if (current == null || value.revision() > current.revision()) {
                writeMetadata(e, value);
            } else if (current.revision() == value.revision() && !current.appliedVersion().equals(value.appliedVersion())) {
                throw new RevisionConflictException();
            }
            return null;
        });
    }

    public Metadata metadata(Engagement e) {
        requireRegion(e.region());
        return tx("metadata", () -> readMetadata(e));
    }

    private Metadata readMetadata(Engagement e) {
        MetadataRow row = metadata.read(e.firm(), e.id());
        return row == null ? null : new Metadata(row.revision(), row.appliedVersion());
    }

    private void writeMetadata(Engagement e, Metadata value) {
        metadata.upsert(e.firm(), e.id(), value.revision(), value.appliedVersion());
    }

    private void lockProjection(Engagement engagement) {
        // Length prefix makes the key unambiguous; a hash collision only adds harmless contention.
        metadata.lock(engagement.firm().length() + ":" + engagement.firm() + engagement.id());
    }

    private Instant currentTime(Instant supplied) {
        return databaseTime ? Instant.ofEpochMilli(control.currentTimeMillis()) : supplied;
    }

    @Override
    public List<Task> tasks(int limit) {
        bounded(limit);
        return tx("tasks", () -> tasks.inspect(limit).stream().map(this::toTask).toList());
    }

    @Override
    public long activeCalls() {
        return tx("activeCalls", tasks::activeCount);
    }

    public long messageCount() {
        return tx("messageCount", messages::count);
    }

    private void requireRegion(String value) {
        if (!region.equals(value)) {
            throw new RegionMismatchException();
        }
    }

    private void bounded(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new InvalidConfigurationException("Batch limit must be 1..1000");
        }
    }

    private <T> T tx(String name, Supplier<T> action) {
        try {
            return transactions.execute(status -> {
                T result = action.get();
                beforeCommit.accept(name);
                return result;
            });
        } catch (DataAccessException | TransactionException e) {
            // Translation happens after rollback, including failures during commit.
            // Do not wrap domain/configuration errors or programming faults as storage failures.
            throw new PersistenceOperationException(name, e);
        }
    }
}
