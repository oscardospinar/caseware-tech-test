package com.caseware.fanout.domain.model;

/** Snapshot plus ownership token. The token fences a worker that lost its lease and is never reused. */
public record Claim(Task task, long token) { }
