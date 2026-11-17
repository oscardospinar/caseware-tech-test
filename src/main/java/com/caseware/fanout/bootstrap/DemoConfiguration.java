package com.caseware.fanout.bootstrap;

import com.caseware.fanout.adapter.in.cli.DemoRunner;
import com.caseware.fanout.adapter.out.demo.DemoInventory;
import com.caseware.fanout.adapter.out.demo.DemoEngagementGateway;
import com.caseware.fanout.application.port.in.PlanPublication;
import com.caseware.fanout.application.port.in.InspectWork;
import com.caseware.fanout.application.port.out.Inventory;
import com.caseware.fanout.application.port.out.EngagementGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Only the composition root selects profiles and binds concrete adapters. */
@Configuration(proxyBeanMethods = false)
@Profile("local & !qa & !prod")
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfiguration {
    @Bean
    Inventory demoInventory(FanOutProperties properties) {
        return new DemoInventory(properties.region());
    }

    @Bean
    EngagementGateway demoGateway(DemoProperties properties) {
        return new DemoEngagementGateway(properties.delay());
    }

    @Bean
    @ConditionalOnProperty(name = "fanout.demo.enabled", havingValue = "true")
    DemoRunner demoRunner(PlanPublication planner, InspectWork inspection, FanOutProperties properties,
                          DemoProperties demo) {
        return new DemoRunner(planner, inspection, properties.region(), demo.timeout(),
                properties.workerEnabled(), demo.pollInterval(), demo.inspectionLimit());
    }
}
