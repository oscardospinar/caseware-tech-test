package com.caseware.fanout.application.service;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.application.port.in.DispatchPendingTasks;
import java.time.Clock;
import com.caseware.fanout.application.port.out.*;
import java.util.List;

public final class OutboxDispatcher implements DispatchPendingTasks {

    private final JobStore store;

    private final MessageQueue queue;

    private final Clock clock;

    public OutboxDispatcher(JobStore store, MessageQueue queue, Clock clock) {
        this.store = store;
        this.queue = queue;
        this.clock = clock;
    }

    @Override
    public int dispatch(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new InvalidConfigurationException("Batch size must be 1..1000");
        }
        List<String> ids = store.unpublished(limit);
        for (String id : ids) {
            queue.send(id, clock.instant());
            // Crash here intentionally produces another notification, never a lost job.
            store.published(id);
        }
        return ids.size();
    }
}
