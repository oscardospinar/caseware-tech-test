package com.caseware.fanout.application.port.in;

import com.caseware.fanout.domain.model.Publication;

public interface PlanPublication {
    /** Plans at most one page; true means more pages remain. */
    boolean step(Publication publication);
}
