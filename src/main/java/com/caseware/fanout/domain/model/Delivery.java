package com.caseware.fanout.domain.model;

/** Queue delivery and receipt used to fence acknowledgment after redelivery. */
public record Delivery(long id, String receipt, String taskId) { }
