package com.caseware.fanout.bootstrap;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentProfilesTest {
    @TempDir
    Path secrets;

    private ApplicationContextRunner settings(String profiles, Map<String, Object> injected) {
        return new ApplicationContextRunner().withUserConfiguration(Settings.class)
                .withInitializer(context -> {
                    if (!profiles.isBlank()) {
                        context.getEnvironment().setActiveProfiles(profiles.split(","));
                    }
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("injected-systemEnvironment", injected));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                });
    }

    private Map<String, Object> credentials() {
        return Map.of("DB_URL", "jdbc:postgresql://database.invalid:5432/example",
                "DB_USERNAME", "injected-user", "DB_PASSWORD", "injected-test-secret", "FANOUT_REGION", "EU");
    }

    @Test
    void defaultEnvironmentIsLocalAndLoadsLocalDefaults() {
        settings("", Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FanOutProperties.class).localConcurrency()).isEqualTo(2);
            assertThat(context.getBean(DataSourceProperties.class).getPassword()).isEqualTo("fanout-local-only");
            assertThat(context.getBean(LiquibaseProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void qaAndProdLoadExternalCredentialsAndDisableStartupMigrations() {
        for (String profile : new String[] {"qa", "prod"}) {
            settings(profile, credentials()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(DataSourceProperties.class).getUsername()).isEqualTo("injected-user");
                assertThat(context.getBean(DataSourceProperties.class).getPassword()).isEqualTo("injected-test-secret");
                assertThat(context.getBean(LiquibaseProperties.class).isEnabled()).isFalse();
                assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class))
                        .isEqualTo(profile.equals("qa") ? 12 : 24);
                assertThat(context.getEnvironment().getProperty("fanout.demo.delay")).isNull();
            });
        }
    }

    @Test
    void missingSecretFailsWithoutFallingBackToLocalCredentials() {
        for (String profile : new String[] {"qa", "prod"}) {
            settings(profile, Map.of("DB_URL", "jdbc:postgresql://database.invalid:5432/example",
                    "DB_USERNAME", "injected-user", "FANOUT_REGION", "EU"))
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasRootCauseInstanceOf(InvalidConfigurationException.class)
                            .hasStackTraceContaining("spring.datasource.password must be injected"));
        }
    }

    @Test
    void missingDatabaseEndpointFailsBeforeOpeningConnections() {
        settings("prod", Map.of("DB_USERNAME", "injected-user", "DB_PASSWORD", "test-secret", "FANOUT_REGION", "EU"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidConfigurationException.class)
                        .hasStackTraceContaining("spring.datasource.url must be injected"));
    }

    @Test
    void multipleEnvironmentProfilesAreRejected() {
        settings("local,prod", credentials()).run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseInstanceOf(InvalidConfigurationException.class)
                .hasStackTraceContaining("Select exactly one environment profile"));
    }

    @Test
    void runtimeSettingsCanBeOverriddenWithEnvironmentVariables() {
        settings("local", Map.of("FANOUT_RUNTIME_DISPATCHBATCHSIZE", "17",
                "FANOUT_RUNTIME_IDLEDELAY", "75ms", "FANOUT_HEARTBEAT_THREADS", "2"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FanOutProperties properties = context.getBean(FanOutProperties.class);
                    assertThat(properties.runtime().dispatchBatchSize()).isEqualTo(17);
                    assertThat(properties.runtime().idleDelay()).isEqualTo(Duration.ofMillis(75));
                    assertThat(properties.heartbeat().threads()).isEqualTo(2);
                });
    }

    @Test
    void heartbeatIntervalMustLeaveTimeForRenewalBeforeExpiry() {
        settings("local", Map.of("FANOUT_HEARTBEAT_INTERVAL", "6s"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(InvalidConfigurationException.class)
                        .hasStackTraceContaining("must not exceed half the lease"));
    }

    @Test
    void mountedSecretsBindThroughConfigTree() throws Exception {
        Files.writeString(secrets.resolve("spring.datasource.url"), "jdbc:postgresql://database.invalid:5432/example");
        Files.writeString(secrets.resolve("spring.datasource.username"), "mounted-user");
        Files.writeString(secrets.resolve("spring.datasource.password"), "mounted-test-secret");
        String importPath = "configtree:" + secrets.toAbsolutePath().toString().replace('\\', '/') + "/";
        settings("qa", Map.of("SPRING_CONFIG_IMPORT", importPath, "FANOUT_REGION", "EU"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataSourceProperties.class).getUsername()).isEqualTo("mounted-user");
                    assertThat(context.getBean(DataSourceProperties.class).getPassword()).isEqualTo("mounted-test-secret");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({FanOutProperties.class, DataSourceProperties.class, LiquibaseProperties.class})
    static class Settings {
        @Bean
        Boolean validEnvironment(Environment environment, DataSourceProperties datasource) {
            EnvironmentValidation.validate(environment, datasource);
            return Boolean.TRUE;
        }
    }
}
