package com.caseware.fanout.adapter.in.cli;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import java.time.Duration;
import java.util.List;
import com.caseware.fanout.application.port.in.PlanPublication;
import com.caseware.fanout.application.port.in.InspectWork;
import com.caseware.fanout.domain.model.Publication;
import com.caseware.fanout.domain.model.State;
import com.caseware.fanout.domain.model.Task;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public final class DemoRunner implements ApplicationRunner {

    private final PlanPublication planner;

    private final InspectWork inspection;

    private final String region;

    private final Duration timeout;

    private final boolean workerEnabled;

    private final Duration pollInterval;
    private final int inspectionLimit;

    public DemoRunner(PlanPublication planner, InspectWork inspection, String region, Duration timeout,
            boolean workerEnabled, Duration pollInterval, int inspectionLimit) {
        this.planner = planner;
        this.inspection = inspection;
        this.region = region;
        this.timeout = timeout;
        this.workerEnabled = workerEnabled;
        this.pollInterval = pollInterval;
        this.inspectionLimit = inspectionLimit;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        if (!workerEnabled) {
            throw new InvalidConfigurationException("The demo requires fanout.worker-enabled=true");
        }
        planPublication();
        awaitCompletion();
        List<Task> tasks = demoTasks();
        validateCompletion(tasks);
        reportCompletion(tasks);
    }

    private void planPublication() {
        Publication publication = new Publication("demo-publication", region, "audit", "v7");
        while (planner.step(publication)) {
            /* reschedulable bounded pages */
        }
    }

    private void awaitCompletion() throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (demoTasks().stream().anyMatch(task -> !task.terminal())) {
            if (System.nanoTime() > deadline) {
                throw new DemoExecutionException("Demo timed out");
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private void validateCompletion(List<Task> tasks) {
        if (tasks.size() != 3 || tasks.stream().anyMatch(task -> task.state() != State.DONE)) {
            throw new DemoExecutionException("Demo did not complete its three selected files");
        }
    }

    private void reportCompletion(List<Task> tasks) {
        tasks.forEach(task -> System.out.println(task.engagement().id() + ": " + task.state() + ", attempts=" + task.attempts()));
        System.out.println("Active remote reservations: " + inspection.activeCalls());
    }

    private List<Task> demoTasks() {
        return inspection.tasks(inspectionLimit).stream().filter(task -> task.publication().id().equals("demo-publication")).toList();
    }
}
