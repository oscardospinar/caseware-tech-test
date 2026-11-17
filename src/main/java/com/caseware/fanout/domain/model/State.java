package com.caseware.fanout.domain.model;

/** Persisted lifecycle state of a verification task. */
public enum State { READY, RUNNING, DONE, FAILED, SUPERSEDED }
