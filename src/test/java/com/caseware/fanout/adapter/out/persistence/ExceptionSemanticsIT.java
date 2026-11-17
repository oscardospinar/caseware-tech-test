package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.application.exception.ApplicationErrorCode;
import com.caseware.fanout.application.exception.InventoryContractException;
import com.caseware.fanout.application.exception.PersistenceOperationException;
import com.caseware.fanout.application.exception.ResourceNotFoundException;
import com.caseware.fanout.application.service.FanOutPlanner;
import com.caseware.fanout.domain.exception.DomainErrorCode;
import com.caseware.fanout.domain.exception.PublicationConflictException;
import com.caseware.fanout.domain.exception.RegionMismatchException;
import com.caseware.fanout.domain.exception.RevisionConflictException;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Metadata;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ExceptionSemanticsIT {
    @Test void domainConflictsAreNotMisclassifiedAsPersistenceFailures() {
        try (Fixture fixture = new Fixture()) {
            fixture.store.start(PUB);
            PublicationConflictException publicationFailure = assertThrows(PublicationConflictException.class,
                    () -> fixture.store.start(new Publication(PUB.id(), "EU", "audit", "different")));
            assertEquals(DomainErrorCode.PUBLICATION_CONFLICT, publicationFailure.code());
            RegionMismatchException regionFailure = assertThrows(RegionMismatchException.class,
                    () -> fixture.store.metadata(new Engagement("Canada", "private-firm", "private-file")));
            assertEquals(DomainErrorCode.REGION_MISMATCH, regionFailure.code());
            assertFalse(regionFailure.getMessage().contains("private"));
            Engagement file = candidate("a", "1").engagement();
            fixture.store.project(file, new Metadata(2, "v2"));
            RevisionConflictException revisionFailure = assertThrows(RevisionConflictException.class,
                    () -> fixture.store.project(file, new Metadata(2, "conflicting")));
            assertEquals(DomainErrorCode.REVISION_CONFLICT, revisionFailure.code());
            assertEquals(new Metadata(2, "v2"), fixture.store.metadata(file));
        }
    }

    @Test void missingResourcesIdentifyTheirKindWithoutExposingIds() {
        try (Fixture fixture = new Fixture()) {
            ResourceNotFoundException taskFailure = assertThrows(ResourceNotFoundException.class,
                    () -> fixture.store.task("private-task"));
            assertEquals(ResourceNotFoundException.Resource.TASK, taskFailure.resource());
            assertEquals(ApplicationErrorCode.RESOURCE_NOT_FOUND, taskFailure.code());
            assertFalse(taskFailure.getMessage().contains("private-task"));
            ResourceNotFoundException publicationFailure = assertThrows(ResourceNotFoundException.class,
                    () -> fixture.store.run(PUB));
            assertEquals(ResourceNotFoundException.Resource.PUBLICATION, publicationFailure.resource());
        }
    }

    @Test void actualSqlFailureIsTranslatedWithCauseAndOperation() {
        try (Fixture fixture = new Fixture()) {
            PersistenceOperationException failure = assertThrows(PersistenceOperationException.class,
                    () -> fixture.store.send("private-missing-task", fixture.clock.instant()));
            assertEquals(ApplicationErrorCode.PERSISTENCE_FAILURE, failure.code());
            assertEquals("send", failure.operation());
            assertInstanceOf(DataIntegrityViolationException.class, failure.getCause());
            assertFalse(failure.getMessage().contains("private-missing-task"));
            assertEquals(0, fixture.store.messageCount());
        }
    }

    @Test void translationPreservesRollbackAndDoesNotSwallowTheOriginalCause() {
        AtomicBoolean fail = new AtomicBoolean(true);
        TransientDataAccessResourceException original = new TransientDataAccessResourceException("Sensitive driver detail");
        try (Fixture fixture = new Fixture(policy(), TestDatabase.newUrl(), operation -> {
            if (operation.equals("savePage") && fail.getAndSet(false)) {
                throw original;
            }
        })) {
            FanOutPlanner planner = new FanOutPlanner(fixture.store, inventory(List.of(candidate("a", "1"))), fixture.clock, 1);
            PersistenceOperationException failure = assertThrows(PersistenceOperationException.class, () -> planner.step(PUB));
            assertEquals("savePage", failure.operation());
            assertSame(original, failure.getCause());
            assertFalse(failure.getMessage().contains("Sensitive"));
            assertNull(fixture.store.run(PUB).cursor());
            assertTrue(fixture.store.tasks(10).isEmpty());
            assertTrue(fixture.store.unpublished(10).isEmpty());
            assertFalse(planner.step(PUB));
            assertEquals(1, fixture.store.tasks(10).size());
        }
    }

    @Test void localPersistenceFailureAfterRemoteSuccessKeepsReservationForRecovery() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (Fixture fixture = new Fixture(policy(), TestDatabase.newUrl(), operation -> {
            if (operation.equals("complete") && fail.getAndSet(false)) {
                throw new TransientDataAccessResourceException("Connection lost");
            }
        })) {
            plan(fixture.store, fixture.clock, List.of(candidate("a", "1")));
            PersistenceOperationException failure = assertThrows(PersistenceOperationException.class, () -> fixture.worker().tick());
            assertEquals("complete", failure.operation());
            Task task = fixture.store.tasks(10).getFirst();
            assertNotNull(task.operationId());
            assertNull(task.errorCode());
            assertEquals(1, fixture.store.activeCalls());
            assertNull(fixture.store.metadata(task.engagement()));
            fixture.clock.advance(fixture.policy.lease().plusSeconds(1));
            fixture.worker().tick();
            assertEquals(State.DONE, fixture.store.task(task.id()).state());
            assertEquals(1, fixture.gateway.started.size());
            assertEquals(1, fixture.store.task(task.id()).attempts());
            assertEquals(0, fixture.store.activeCalls());
        }
    }

    @Test void missingInventoryPageFailsWithoutAdvancingTheRun() {
        try (Fixture fixture = new Fixture()) {
            FanOutPlanner planner = new FanOutPlanner(fixture.store, (publication, cursor, limit) -> null, fixture.clock, 10);
            InventoryContractException failure = assertThrows(InventoryContractException.class, () -> planner.step(PUB));
            assertEquals(ApplicationErrorCode.INVALID_INVENTORY_PAGE, failure.code());
            assertNull(fixture.store.run(PUB).cursor());
            assertTrue(fixture.store.tasks(10).isEmpty());
        }
    }
}
