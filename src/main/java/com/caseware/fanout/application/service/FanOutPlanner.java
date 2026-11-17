package com.caseware.fanout.application.service;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.application.exception.InventoryContractException;
import com.caseware.fanout.domain.exception.RegionMismatchException;
import com.caseware.fanout.application.port.in.PlanPublication;
import java.time.Clock;
import java.util.Objects;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.Run;
import com.caseware.fanout.application.port.out.Inventory;
import com.caseware.fanout.application.port.out.JobStore;

/**
 * One bounded page per step; suitable for rescheduling and recovery rather than a giant fan-out transaction.
 */
public final class FanOutPlanner implements PlanPublication {

    private final JobStore store;

    private final Inventory inventory;

    private final Clock clock;

    private final int pageSize;

    public FanOutPlanner(JobStore store, Inventory inventory, Clock clock, int pageSize) {
        if (pageSize < 1 || pageSize > 1000) {
            throw new InvalidConfigurationException("Page size must be 1..1000");
        }
        this.store = store;
        this.inventory = inventory;
        this.clock = clock;
        this.pageSize = pageSize;
    }

    @Override
    public boolean step(Publication publication) {
        Run run = store.start(publication);
        if (run.finished()) {
            return false;
        }
        Page page = inventory.fetch(publication, run.cursor(), pageSize);
        validatePage(run, page);
        store.savePage(run, page, clock.instant());
        return !store.run(publication).finished();
    }

    private void validatePage(Run run, Page page) {
        if (page == null) {
            throw new InventoryContractException("Inventory returned no page");
        }
        if (page.candidates().size() > pageSize || (page.nextCursor() != null && Objects.equals(page.nextCursor(), run.cursor()))) {
            throw new InventoryContractException("Inventory exceeded its page bound or did not advance its cursor");
        }
        for (Candidate candidate : page.candidates()) {
            if (!candidate.engagement().region().equals(run.publication().region())) {
                throw new RegionMismatchException();
            }
        }
    }
}
