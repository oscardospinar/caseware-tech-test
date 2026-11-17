package com.caseware.fanout.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import com.caseware.fanout.adapter.in.cli.DemoRunner;

/** Spring owns composition and shutdown; the worker's domain code does not depend on Spring. */
@SpringBootApplication
public class FanOutApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FanOutApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        // Only the explicitly enabled finite demo exits; ordinary workers keep polling until shutdown.
        if (!context.getBeansOfType(DemoRunner.class).isEmpty()) {
            context.close();
        }
    }
}
