package com.caseware.fanout.bootstrap;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fanout.demo")
public record DemoProperties(Duration delay, Duration timeout, Duration pollInterval, int inspectionLimit) {
    public DemoProperties {
        if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new InvalidConfigurationException("fanout.demo.delay must be between zero and one minute");
        }
        if (timeout == null || timeout.toMillis() < 1 || pollInterval == null || pollInterval.toMillis() < 1) {
            throw new InvalidConfigurationException("Demo timeout and polling interval must be positive");
        }
        if (inspectionLimit < 3 || inspectionLimit > 1000) {
            throw new InvalidConfigurationException("Demo inspection limit must cover its three files and not exceed 1000");
        }
    }
}
