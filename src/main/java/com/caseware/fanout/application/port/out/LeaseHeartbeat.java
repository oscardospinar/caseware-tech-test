package com.caseware.fanout.application.port.out;

import com.caseware.fanout.domain.model.Claim;
import com.caseware.fanout.domain.model.Delivery;

public interface LeaseHeartbeat {
    /** Keeps local ownership alive; renewal failure must never release remote capacity. */
    Registration start(Claim claim, Delivery delivery);

    interface Registration extends AutoCloseable {
        @Override void close();
    }
}
