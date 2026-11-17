package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.RemoteResult;

public interface EngagementGateway {
    /**
     * Blocks on the slow operation (normally ~1 minute). The remote owner must atomically deduplicate
     * operationId, including concurrent/late requests, and retain terminal outcomes for recovery.
     * A new ID is used only after an earlier attempt is definitely terminal.
     */
    RemoteResult execute(String operationId, Engagement engagement) throws Exception;
    /** Status queries never start another load. NOT_FOUND may be safely resubmitted with the SAME ID. */
    RemoteResult lookup(String operationId) throws Exception;
}
