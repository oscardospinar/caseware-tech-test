package com.caseware.fanout.bootstrap;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import java.util.List;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Validates deployment inputs without echoing connection details or credentials. */
final class EnvironmentValidation {
    private EnvironmentValidation() {
    }

    static void validate(Environment environment, DataSourceProperties datasource) {
        long environments = List.of("local", "qa", "prod").stream()
                .filter(profile -> environment.acceptsProfiles(Profiles.of(profile))).count();
        if (environments != 1) {
            throw new InvalidConfigurationException("Select exactly one environment profile: local, qa or prod");
        }
        requireValue(datasource.getUrl(), "spring.datasource.url");
        if (!datasource.getUrl().startsWith("jdbc:postgresql:")) {
            throw new InvalidConfigurationException("The repositories require PostgreSQL");
        }
        requireValue(datasource.getUsername(), "spring.datasource.username");
        if (environment.acceptsProfiles(Profiles.of("qa", "prod"))) {
            requireValue(datasource.getPassword(), "spring.datasource.password");
        }
    }

    private static void requireValue(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigurationException(property + " must be injected for the selected environment");
        }
    }
}
