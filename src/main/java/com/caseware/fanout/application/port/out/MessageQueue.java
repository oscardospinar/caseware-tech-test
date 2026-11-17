package com.caseware.fanout.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import com.caseware.fanout.domain.model.Delivery;

public interface MessageQueue {
    void send(String taskId, Instant now);
    Optional<Delivery> receive(Instant now, Duration visibility);
    boolean acknowledge(Delivery delivery);
    boolean defer(Delivery delivery, Instant dueAt);
    boolean renew(Delivery delivery, Instant now, Duration visibility);
}
