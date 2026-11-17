package com.caseware.fanout.bootstrap;


import com.caseware.fanout.domain.policy.Policy;
import com.caseware.fanout.application.service.*;
import com.caseware.fanout.application.port.out.*;
import com.caseware.fanout.adapter.out.persistence.SpringDataStore;
import com.caseware.fanout.adapter.out.scheduling.ScheduledLeaseHeartbeat;
import com.caseware.fanout.adapter.in.polling.WorkerRuntime;
import com.caseware.fanout.adapter.in.cli.DemoRunner;
import com.caseware.fanout.application.port.in.PlanPublication;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.State;
import java.time.Duration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import java.util.List;
import com.caseware.fanout.adapter.out.persistence.TestDatabase;
import org.junit.jupiter.api.Test;
import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.domain.exception.InvalidAdmissionLimitsException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class SpringBootWiringIT {
    private ApplicationContextRunner demoContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FanOutConfiguration.class, DemoConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.datasource.url=" + TestDatabase.newUrl(), "spring.datasource.username=" + TestDatabase.username(), "spring.datasource.password=" + TestDatabase.password(), "fanout.worker-enabled=false");
    }

    @Test void bindsTypedConfigurationAndWiresExistingPorts() {
        demoContext().withPropertyValues("fanout.local-concurrency=2", "fanout.page-size=17",
                "fanout.limits.max-active=3", "fanout.limits.max-per-firm=2", "fanout.demo.delay=25ms")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FanOutPlanner.class)
                            .hasSingleBean(OutboxDispatcher.class).hasSingleBean(JobStore.class)
                            .hasSingleBean(MessageQueue.class).doesNotHaveBean(WorkerRuntime.class);
                    assertThat(context.getBean(JobStore.class)).isSameAs(context.getBean(MessageQueue.class));
                    assertThat(context.getBean(Policy.class).maxActive()).isEqualTo(3);
                    assertThat(context.getBean(DemoProperties.class).delay()).isEqualTo(Duration.ofMillis(25));
                    assertThat(context.getBean(FanOutProperties.class).pageSize()).isEqualTo(17);
                });
    }

    @Test void poolAndSessionLockTimeoutFollowProperties() {
        demoContext().withPropertyValues("spring.datasource.hikari.maximum-pool-size=3",
                "spring.datasource.hikari.minimum-idle=0", "fanout.database.lock-timeout=1250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource source = context.getBean(HikariDataSource.class);
                    assertThat(source.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(source.getMinimumIdle()).isZero();
                    assertThat(new JdbcTemplate(source).queryForObject("SHOW lock_timeout", String.class)).isEqualTo("1250ms");
                });
    }

    @Test void invalidLocalConcurrencyFailsAtStartup() {
        demoContext().withPropertyValues("fanout.local-concurrency=0").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseInstanceOf(InvalidConfigurationException.class));
    }

    @Test void invalidSharedLimitsFailBeforeWorkerStarts() {
        demoContext().withPropertyValues("fanout.limits.max-active=1", "fanout.limits.max-per-firm=2")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidAdmissionLimitsException.class));
    }

    @Test void nonDemoProfileDoesNotSilentlyUseFakeAdapters() {
        new ApplicationContextRunner()
                .withUserConfiguration(FanOutConfiguration.class, DemoConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("fanout.region=EU", "spring.liquibase.enabled=true")
                .withPropertyValues("spring.datasource.url=" + TestDatabase.newUrl(), "spring.datasource.username=" + TestDatabase.username(), "spring.datasource.password=" + TestDatabase.password(), "fanout.worker-enabled=false")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class));
    }

    @Test void nonDemoProfileAcceptsExplicitInventoryAdapter() {
        new ApplicationContextRunner()
                .withUserConfiguration(FanOutConfiguration.class, DemoConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("qa"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("fanout.region=EU", "spring.liquibase.enabled=true")
                .withBean(Inventory.class, () -> (publication, cursor, limit) -> new Page(List.of(), null))
                .withPropertyValues("spring.datasource.url=" + TestDatabase.newUrl(), "spring.datasource.username=" + TestDatabase.username(), "spring.datasource.password=" + TestDatabase.password(), "fanout.worker-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FanOutPlanner.class)
                            .doesNotHaveBean(EngagementGateway.class).doesNotHaveBean("demoRunner");
                });
    }

    @Test void bootRunsDemoAndClosesExecutorsWithTheContext() {
        WorkerRuntime runtime;
        ScheduledLeaseHeartbeat heartbeat;
        HikariDataSource dataSource;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FanOutApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=local", "--spring.datasource.url=" + TestDatabase.newUrl(), "--spring.datasource.username=" + TestDatabase.username(), "--spring.datasource.password=" + TestDatabase.password(),
                        "--fanout.demo.enabled=true", "--fanout.demo.delay=0ms", "--fanout.demo.timeout=10s", "--logging.level.root=WARN")) {
            SpringDataStore store = context.getBean(SpringDataStore.class);
            dataSource = context.getBean(HikariDataSource.class);
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM databasechangelog",Integer.class))
                    .isEqualTo(2);
            assertThat(store.tasks(10)).hasSize(3).allMatch(task -> task.state() == State.DONE);
            assertThat(store.activeCalls()).isZero();
            runtime = context.getBean(WorkerRuntime.class);
            heartbeat = context.getBean(ScheduledLeaseHeartbeat.class);
            assertThat(runtime.isTerminated()).isFalse();
        }
        assertThat(runtime.isTerminated()).isTrue();
        assertThat(heartbeat.isTerminated()).isTrue();
        assertThat(dataSource.isClosed()).isTrue();
    }

    @Test void continuousWorkerProcessesStoredAndLaterWorkWithoutStartingTheDemo() {
        String url = TestDatabase.newUrl();
        try (ConfigurableApplicationContext preparation = localApplication(url, "--fanout.worker-enabled=false")) {
            assertThat(preparation.getBeansOfType(DemoRunner.class)).isEmpty();
            plan(preparation, "stored-publication");
            assertThat(preparation.getBean(JobStore.class).tasks(10))
                    .hasSize(3).allMatch(task -> task.state() == State.READY);
        }

        WorkerRuntime runtime;
        ScheduledLeaseHeartbeat heartbeat;
        HikariDataSource dataSource;
        try (ConfigurableApplicationContext worker = localApplication(url, "--fanout.worker-enabled=true")) {
            assertThat(worker.getBeansOfType(DemoRunner.class)).isEmpty();
            JobStore store = worker.getBean(JobStore.class);
            awaitCompletion(store, 3);
            // Once the persisted backlog drains, the same worker must accept later work.
            plan(worker, "later-publication");
            awaitCompletion(store, 6);
            assertThat(worker.isActive()).isTrue();
            runtime = worker.getBean(WorkerRuntime.class);
            heartbeat = worker.getBean(ScheduledLeaseHeartbeat.class);
            dataSource = worker.getBean(HikariDataSource.class);
            assertThat(runtime.isTerminated()).isFalse();
        }
        assertThat(runtime.isTerminated()).isTrue();
        assertThat(heartbeat.isTerminated()).isTrue();
        assertThat(dataSource.isClosed()).isTrue();
    }

    private ConfigurableApplicationContext localApplication(String url, String workerSetting) {
        return new SpringApplicationBuilder(FanOutApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=local", "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + TestDatabase.username(),
                        "--spring.datasource.password=" + TestDatabase.password(),
                        "--fanout.demo.delay=0ms", "--logging.level.root=WARN", workerSetting);
    }

    private void plan(ConfigurableApplicationContext context, String publicationId) {
        PlanPublication planner = context.getBean(PlanPublication.class);
        Publication publication = new Publication(publicationId, "EU", "audit", "v7");
        while (planner.step(publication)) {
            // Resume bounded pages until the publication is durably planned.
        }
    }

    private void awaitCompletion(JobStore store, int taskCount) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(store.tasks(10)).hasSize(taskCount).allMatch(task -> task.state() == State.DONE);
            assertThat(store.activeCalls()).isZero();
        });
    }
}
