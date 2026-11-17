package com.caseware.fanout.application.port.in;

public interface DispatchPendingTasks {
    int dispatch(int limit);
}
