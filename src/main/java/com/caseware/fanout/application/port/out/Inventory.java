package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;

public interface Inventory {
    /** Stable, bounded page; null cursor starts a snapshot, null nextCursor ends it. */
    Page fetch(Publication publication, String cursor, int limit);
}
