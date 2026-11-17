package com.caseware.fanout.domain.model;

/** Observed status of a remote operation, including unresolved outcomes. */
public enum RemoteState { NOT_FOUND, RUNNING, SUCCEEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE }
