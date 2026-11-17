package com.caseware.fanout.application.service;

import java.util.List;
import com.caseware.fanout.domain.model.Task;
import com.caseware.fanout.application.port.in.InspectWork;
import com.caseware.fanout.application.port.out.JobStore;

public final class WorkInspection implements InspectWork {
    private final JobStore store;

    public WorkInspection(JobStore store) { this.store = store; }

    @Override public List<Task> tasks(int limit) { return store.tasks(limit); }
    @Override public long activeCalls() { return store.activeCalls(); }
}
