package com.caseware.fanout.application.port.in;

import java.util.List;
import com.caseware.fanout.domain.model.Task;

/** Bounded operational inspection, not the product query API. */
public interface InspectWork {
    List<Task> tasks(int limit);
    long activeCalls();
}
