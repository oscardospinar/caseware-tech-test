package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.adapter.out.persistence.repository.*;
import com.caseware.fanout.bootstrap.PersistenceConfiguration;
import com.caseware.fanout.domain.policy.Policy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import java.util.Map;
import java.util.function.Consumer;

/** Real migrations, repository proxies and transaction manager; only failure injection is test-specific. */
final class PersistenceTestContext implements AutoCloseable {
    private final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
    private final SpringDataStore store;

    PersistenceTestContext(String url, String region, Policy policy) { this(url,region,policy,ignored -> { }); }

    PersistenceTestContext(String url, String region, Policy policy, Consumer<String> beforeCommit) {
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of(
                    "spring.datasource.url",url,"fanout.region",region,
                    "spring.datasource.username",TestDatabase.username(),
                    "spring.datasource.password",TestDatabase.password(),
                    "spring.liquibase.change-log","classpath:db/changelog/postgresql/db.changelog-master.xml")));
            new ConfigDataApplicationContextInitializer().initialize(context);
            context.registerBean(Policy.class,() -> policy);
            context.register(PersistenceConfiguration.class);
            context.refresh();
            store=new SpringDataStore(context.getBean(ControlRepository.class),context.getBean(RunRepository.class),
                    context.getBean(TaskRepository.class),context.getBean(FirmRepository.class),
                    context.getBean(OutboxRepository.class),context.getBean(MessageRepository.class),
                    context.getBean(MetadataRepository.class),context.getBean(PlatformTransactionManager.class),
                    region,policy,beforeCommit);
        } catch (RuntimeException e) { context.close(); throw new IllegalStateException("Persistence startup failed",e); }
    }

    SpringDataStore store() { return store; }
    SpringDataStore productionStore() { return context.getBean(SpringDataStore.class); }
    DataSource dataSource() { return context.getBean(DataSource.class); }
    @Override public void close() { context.close(); }
}
