package com.caseware.fanout.adapter.in.polling;

import com.caseware.fanout.application.exception.InvalidConfigurationException;
import com.caseware.fanout.application.exception.ApplicationException;
import com.caseware.fanout.domain.exception.DomainException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.caseware.fanout.application.port.in.ProcessNextTask;
import com.caseware.fanout.application.port.in.DispatchPendingTasks;

/**
 * Driving adapter: fixed local concurrency and bounded dispatch batches.
 */
public final class WorkerRuntime implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(WorkerRuntime.class.getName());

    private final AtomicBoolean stopping = new AtomicBoolean();

    private final ExecutorService threads;

    private final int dispatchBatchSize;
    private final Duration idleDelay;
    private final Duration failureDelay;
    private final Duration shutdownTimeout;

    public WorkerRuntime(ProcessNextTask worker, DispatchPendingTasks dispatcher, int localConcurrency,
            int dispatchBatchSize, Duration idleDelay, Duration failureDelay, Duration shutdownTimeout) {
        if (localConcurrency < 1 || localConcurrency > 128) {
            throw new InvalidConfigurationException("Local concurrency must be 1..128");
        }
        this.dispatchBatchSize = dispatchBatchSize;
        this.idleDelay = idleDelay;
        this.failureDelay = failureDelay;
        this.shutdownTimeout = shutdownTimeout;
        threads = Executors.newFixedThreadPool(localConcurrency);
        for (int i = 0; i < localConcurrency; i++) {
            threads.submit(() -> runWorker(worker, dispatcher));
        }
    }

    private void runWorker(ProcessNextTask worker, DispatchPendingTasks dispatcher) {
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            processBatch(worker, dispatcher);
        }
    }

    private void processBatch(ProcessNextTask worker, DispatchPendingTasks dispatcher) {
        try {
            dispatcher.dispatch(dispatchBatchSize);
            if (!worker.tick()) {
                pause(idleDelay);
            }
        } catch (RuntimeException e) {
            // Lease/visibility expiry recovers the delivery; avoid printing tenant/error payloads.
            LOGGER.log(Level.WARNING, "Worker tick failed; delivery remains recoverable. Code: {0}", failureCode(e));
            pause(failureDelay);
        }
    }

    private void pause(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.INFO, "Worker wait interrupted; polling thread will stop.");
        }
    }

    public boolean isTerminated() {
        return threads.isTerminated();
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof DomainException domainFailure) {
            return domainFailure.code().name();
        }
        if (failure instanceof ApplicationException applicationFailure) {
            return applicationFailure.code().name();
        }
        return failure.getClass().getSimpleName();
    }

    @Override
    public void close() {
        stopping.set(true);
        threads.shutdown();
        try {
            if (!threads.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                threads.shutdownNow();
                threads.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            threads.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.log(Level.INFO, "Worker shutdown wait interrupted; immediate shutdown requested.");
        }
        // Remote operations that outlive shutdown remain reserved durably for the next process.
    }
}
