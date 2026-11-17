package com.caseware.fanout.application.service;

import com.caseware.fanout.application.exception.PersistenceOperationException;
import com.caseware.fanout.application.port.out.EngagementGateway;
import com.caseware.fanout.application.port.out.JobStore;
import com.caseware.fanout.application.port.out.LeaseHeartbeat;
import com.caseware.fanout.application.port.out.LeaseHeartbeat.Registration;
import com.caseware.fanout.application.port.out.MessageQueue;
import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.RemoteResult;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import com.caseware.fanout.domain.policy.Policy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Application decisions only; every infrastructure dependency is a mocked port. */
@ExtendWith(MockitoExtension.class)
class FanOutWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Publication PUBLICATION = new Publication("publication-1", "EU", "audit", "v7");
    private static final Engagement ENGAGEMENT = new Engagement("EU", "firm-1", "engagement-1");
    private static final Delivery DELIVERY = new Delivery(1, "receipt-1", "task-1");
    private static final Policy POLICY = new Policy(2, 1, Duration.ZERO, 3, Duration.ofSeconds(30),
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(16), 3, Duration.ofSeconds(10));
    private static final Instant POLL_AT = NOW.plus(POLICY.pollDelay());

    @Mock private JobStore store;
    @Mock private MessageQueue queue;
    @Mock private EngagementGateway gateway;
    @Mock private LeaseHeartbeat heartbeats;
    @Mock private Registration registration;
    private FanOutWorker worker;

    @BeforeEach void createWorker() {
        worker = new FanOutWorker(store, queue, gateway, POLICY, Clock.fixed(NOW, ZoneOffset.UTC),
                heartbeats, () -> 0.0);
    }

    @Test void emptyQueueDoesNotAccessStorageOrGateway() {
        when(queue.receive(NOW, POLICY.lease())).thenReturn(Optional.empty());

        assertFalse(worker.tick());

        verifyNoInteractions(store, gateway, heartbeats);
    }

    @Test void duplicateTerminalDeliveryIsAcknowledgedWithoutClaimingOrExecuting() {
        enqueue(task(State.DONE, "operation-1", NOW));

        assertTrue(worker.tick());

        verify(queue).acknowledge(DELIVERY);
        verify(store, never()).claim(any(), any(), any());
        verifyNoInteractions(gateway, heartbeats);
    }

    @Test void unavailableClaimDefersUntilTheTaskIsAvailable() {
        Instant availableAt = NOW.plusSeconds(20);
        enqueue(task(State.READY, null, availableAt));
        when(store.claim(DELIVERY.taskId(), NOW, POLICY.lease())).thenReturn(Optional.empty());

        worker.tick();

        verify(queue).defer(DELIVERY, availableAt);
        verify(queue, never()).acknowledge(any());
        verifyNoInteractions(gateway, heartbeats);
    }

    @Test void withdrawnPublicationIsSupersededBeforeRemoteWorkStarts() {
        Claim claim = claim(task(State.READY, null, NOW));
        when(store.targetActive(PUBLICATION)).thenReturn(false);
        when(store.supersede(claim, NOW)).thenReturn(true);

        worker.tick();

        verify(queue).acknowledge(DELIVERY);
        verify(store, never()).admit(any(), any());
        verifyNoInteractions(gateway, heartbeats);
    }

    @Test void deniedCapacityDefersWithoutCallingTheGateway() {
        Claim claim = claim(task(State.RUNNING, "operation-1", NOW));
        when(store.admit(claim, NOW)).thenReturn(Optional.empty());
        when(store.defer(claim, NOW, POLL_AT, "ADMISSION_DEFERRED")).thenReturn(true);

        worker.tick();

        verify(queue).defer(DELIVERY, POLL_AT);
        verifyNoInteractions(gateway, heartbeats);
    }

    @Test void recoveredSuccessDoesNotResubmitTheOperation() throws Exception {
        Claim active = admit();
        RemoteResult result = RemoteResult.success(1, "v7");
        when(gateway.lookup("operation-1")).thenReturn(result);
        when(store.complete(active, result, NOW, NOW.plusSeconds(1))).thenReturn(true);
        when(store.task(DELIVERY.taskId())).thenReturn(active.task(), task(State.DONE, "operation-1", NOW));

        worker.tick();

        verify(gateway, never()).execute(any(), any());
        verify(queue).acknowledge(DELIVERY);
        verify(registration).close();
    }

    @Test void missingRemoteOperationIsResubmittedWithItsExistingIdAfterAuthorization() throws Exception {
        Claim active = admit();
        when(gateway.lookup("operation-1")).thenReturn(RemoteResult.notFound());
        when(store.authorizeSubmission(active, NOW)).thenReturn(true);
        when(gateway.execute("operation-1", ENGAGEMENT)).thenReturn(RemoteResult.running());
        when(store.defer(active, NOW, POLL_AT, "REMOTE_PENDING")).thenReturn(true);

        worker.tick();

        verify(gateway).execute("operation-1", ENGAGEMENT);
        verify(queue).defer(DELIVERY, POLL_AT);
        verify(store, never()).complete(any(), any(), any(), any());
        verify(registration).close();
    }

    @Test void deniedSubmissionDoesNotExecuteEvenWhenRemoteOperationIsMissing() throws Exception {
        Claim active = admit();
        when(gateway.lookup("operation-1")).thenReturn(RemoteResult.notFound());
        when(store.authorizeSubmission(active, NOW)).thenReturn(false);
        when(store.defer(active, NOW, POLL_AT, "SUBMISSION_DEFERRED")).thenReturn(true);

        worker.tick();

        verify(gateway, never()).execute(any(), any());
        verify(queue).defer(DELIVERY, POLL_AT);
        verify(registration).close();
    }

    @Test void uncertainRemoteOutcomeIsDeferredWithoutCompletingOrAcknowledging() throws Exception {
        Claim active = admit();
        when(gateway.lookup("operation-1")).thenThrow(new IOException("Connection lost"));
        when(store.defer(active, NOW, POLL_AT, "REMOTE_UNCERTAIN")).thenReturn(true);

        worker.tick();

        verify(queue).defer(DELIVERY, POLL_AT);
        verify(queue, never()).acknowledge(any());
        verify(store, never()).complete(any(), any(), any(), any());
        verify(registration).close();
    }

    @Test void storageFailureDuringUncertaintyPropagatesWithOriginalCause() throws Exception {
        Claim active = admit();
        IOException cause = new IOException("Storage unavailable");
        PersistenceOperationException failure = new PersistenceOperationException("defer", cause);
        when(gateway.lookup("operation-1")).thenThrow(new IOException("Connection lost"));
        when(store.defer(active, NOW, POLL_AT, "REMOTE_UNCERTAIN")).thenThrow(failure);

        PersistenceOperationException thrown = assertThrows(PersistenceOperationException.class, worker::tick);

        assertSame(failure, thrown);
        assertSame(cause, thrown.getCause());
        verify(queue, never()).defer(any(), any());
        verify(queue, never()).acknowledge(any());
        verify(registration).close();
    }

    @Test void lostOwnershipPreventsAcknowledgingRemoteSuccess() throws Exception {
        Claim active = admit();
        RemoteResult result = RemoteResult.success(1, "v7");
        when(gateway.lookup("operation-1")).thenReturn(result);
        when(store.complete(active, result, NOW, NOW.plusSeconds(1))).thenReturn(false);

        worker.tick();

        verify(queue, never()).acknowledge(any());
        verify(queue, never()).defer(any(), any());
        verify(registration).close();
    }

    private Task task(State state, String operationId, Instant availableAt) {
        return new Task(DELIVERY.taskId(), PUBLICATION, ENGAGEMENT, 1, state, 1, operationId, availableAt, null);
    }

    private void enqueue(Task task) {
        when(queue.receive(NOW, POLICY.lease())).thenReturn(Optional.of(DELIVERY));
        when(store.task(task.id())).thenReturn(task);
    }

    private Claim claim(Task task) {
        enqueue(task);
        Claim claim = new Claim(task, 1);
        when(store.claim(task.id(), NOW, POLICY.lease())).thenReturn(Optional.of(claim));
        return claim;
    }

    private Claim admit() {
        Claim claim = claim(task(State.RUNNING, "operation-1", NOW));
        when(store.admit(claim, NOW)).thenReturn(Optional.of(claim));
        when(heartbeats.start(claim, DELIVERY)).thenReturn(registration);
        return claim;
    }
}
