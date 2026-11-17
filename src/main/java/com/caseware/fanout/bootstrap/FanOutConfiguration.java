package com.caseware.fanout.bootstrap;

import com.caseware.fanout.domain.policy.Policy;
import com.caseware.fanout.adapter.out.persistence.DatabaseClock;
import com.caseware.fanout.adapter.out.persistence.repository.ControlRepository;
import com.caseware.fanout.application.service.*;
import com.caseware.fanout.adapter.in.polling.WorkerRuntime;
import com.caseware.fanout.adapter.out.scheduling.ScheduledLeaseHeartbeat;
import com.caseware.fanout.application.port.in.*;
import com.caseware.fanout.application.port.out.*;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FanOutProperties.class)
@Import(PersistenceConfiguration.class)
public class FanOutConfiguration {
    @Bean
    Clock clock(ControlRepository control) { return new DatabaseClock(control); }

    @Bean
    Policy policy(FanOutProperties properties) { return properties.limits().toPolicy(); }

    @Bean
    FanOutPlanner fanOutPlanner(JobStore store, Inventory inventory, Clock clock, FanOutProperties properties) {
        return new FanOutPlanner(store, inventory, clock, properties.pageSize());
    }

    @Bean
    OutboxDispatcher outboxDispatcher(JobStore store, MessageQueue queue, Clock clock) {
        return new OutboxDispatcher(store, queue, clock);
    }

    @Bean
    WorkInspection workInspection(JobStore store) { return new WorkInspection(store); }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "fanout.worker-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledLeaseHeartbeat leaseHeartbeat(JobStore store, MessageQueue queue, Policy policy, Clock clock,
            FanOutProperties properties) {
        return new ScheduledLeaseHeartbeat(store, queue, policy, clock,
                Executors.newScheduledThreadPool(properties.heartbeat().threads()),
                properties.heartbeat().interval(), properties.heartbeat().shutdownTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "fanout.worker-enabled", havingValue = "true", matchIfMissing = true)
    FanOutWorker fanOutWorker(JobStore store, MessageQueue queue, EngagementGateway gateway,
                             Policy policy, Clock clock, LeaseHeartbeat heartbeats) {
        return new FanOutWorker(store, queue, gateway, policy, clock, heartbeats,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "fanout.worker-enabled", havingValue = "true", matchIfMissing = true)
    WorkerRuntime workerRuntime(ProcessNextTask worker, DispatchPendingTasks dispatcher, FanOutProperties properties) {
        // Dependency order: polling threads, heartbeat scheduler, then JDBC storage.
        return new WorkerRuntime(worker, dispatcher, properties.localConcurrency(),
                properties.runtime().dispatchBatchSize(), properties.runtime().idleDelay(),
                properties.runtime().failureDelay(), properties.runtime().shutdownTimeout());
    }
}
