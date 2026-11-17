package com.caseware.fanout.bootstrap;

import com.caseware.fanout.adapter.out.persistence.SpringDataStore;
import com.caseware.fanout.adapter.out.persistence.repository.*;
import com.caseware.fanout.domain.policy.Policy;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

/**
 * Composition only: Liquibase owns DDL; repositories share one datasource and transaction manager.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcRepositories(basePackageClasses = ControlRepository.class)
@EnableConfigurationProperties({ FanOutProperties.class, DataSourceProperties.class, LiquibaseProperties.class })
public class PersistenceConfiguration extends AbstractJdbcConfiguration {

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties, FanOutProperties fanout, Environment environment) {
        EnvironmentValidation.validate(environment, properties);
        HikariDataSource source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setConnectionInitSql("SET lock_timeout = '" + fanout.database().lockTimeout().toMillis() + "ms'");
        return source;
    }

    @Bean
    SpringLiquibase liquibase(DataSource source, LiquibaseProperties properties) {
        SpringLiquibase migration = new SpringLiquibase();
        migration.setDataSource(source);
        migration.setChangeLog(properties.getChangeLog());
        migration.setShouldRun(properties.isEnabled());
        migration.setDefaultSchema(properties.getDefaultSchema());
        return migration;
    }

    @Bean
    NamedParameterJdbcOperations namedParameterJdbcOperations(DataSource source) {
        return new NamedParameterJdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
        return new JdbcTransactionManager(source);
    }

    @Bean
    SpringDataStore springDataStore(ControlRepository control, RunRepository runs, TaskRepository tasks, FirmRepository firms, OutboxRepository outbox, MessageRepository messages, MetadataRepository metadata, PlatformTransactionManager manager, FanOutProperties properties, Policy policy, SpringLiquibase migration) {
        // Explicit dependency guarantees migrations finish before initialization and worker startup.
        return new SpringDataStore(control, runs, tasks, firms, outbox, messages, metadata, manager, properties.region(), policy);
    }
}
