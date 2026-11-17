package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.application.service.*;
import com.caseware.fanout.application.port.out.*;
import java.util.List;
import static com.caseware.fanout.adapter.out.persistence.TestSupport.*;
import com.caseware.fanout.domain.model.Delivery;
import com.caseware.fanout.domain.model.Claim;

/**
 * Forked JVM intentionally exits without finally/close, after reserving but before issuing a call.
 */
public final class CrashProcess {

    public static void main(String[] args) {
        MutableClock clock = new MutableClock();
        PersistenceTestContext database = new PersistenceTestContext(args[0], "EU", policy());
        SpringDataStore store = database.store();
        plan(store, clock, List.of(candidate("a", "1")));
        Delivery delivery = store.receive(clock.instant(), policy().lease()).orElseThrow();
        Claim claim = store.claim(delivery.taskId(), clock.instant(), policy().lease()).orElseThrow();
        store.admit(claim, clock.instant()).orElseThrow();
        Runtime.getRuntime().halt(23);
    }
}
