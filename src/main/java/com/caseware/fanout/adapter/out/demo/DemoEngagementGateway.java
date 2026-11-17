package com.caseware.fanout.adapter.out.demo;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.caseware.fanout.application.port.out.EngagementGateway;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.RemoteResult;

/**
 * In-memory demo only: results do not survive process restart.
 */
public final class DemoEngagementGateway implements EngagementGateway {

    private final Map<String, RemoteResult> outcomes = new ConcurrentHashMap<>();

    private final Duration delay;

    public DemoEngagementGateway(Duration delay) {
        this.delay = delay;
    }

    @Override
    public RemoteResult lookup(String id) {
        return outcomes.getOrDefault(id, RemoteResult.notFound());
    }

    @Override
    public RemoteResult execute(String id, Engagement engagement) throws InterruptedException {
        RemoteResult previous = outcomes.putIfAbsent(id, RemoteResult.running());
        if (previous != null) {
            return previous;
        }
        Thread.sleep(delay.toMillis());
        RemoteResult result = RemoteResult.success(1, "v3");
        outcomes.put(id, result);
        return result;
    }
}
