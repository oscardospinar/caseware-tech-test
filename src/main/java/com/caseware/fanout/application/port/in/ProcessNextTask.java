package com.caseware.fanout.application.port.in;

public interface ProcessNextTask {
    /** Processes one delivery; false means no message is currently visible. */
    boolean tick();
}
