package com.caseware.fanout.adapter.out.persistence;

import com.caseware.fanout.adapter.out.persistence.repository.ControlRepository;
import com.caseware.fanout.application.exception.PersistenceOperationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.dao.DataAccessException;

/** All replicas use PostgreSQL time for scheduling rather than their host clocks. */
public final class DatabaseClock extends Clock {
    private final ControlRepository control;
    private final ZoneId zone;

    public DatabaseClock(ControlRepository control) {
        this(control, ZoneOffset.UTC);
    }

    private DatabaseClock(ControlRepository control, ZoneId zone) {
        this.control = control;
        this.zone = Objects.requireNonNull(zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requested) {
        return new DatabaseClock(control, requested);
    }

    @Override
    public Instant instant() {
        try {
            return Instant.ofEpochMilli(control.currentTimeMillis());
        } catch (DataAccessException e) {
            throw new PersistenceOperationException("databaseTime", e);
        }
    }
}
