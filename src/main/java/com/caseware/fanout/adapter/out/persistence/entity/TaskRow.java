package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("tasks")
public record TaskRow(
        @Id String id,
        String runId,
        String region,
        String firm,
        String engagement,
        long requiredRevision,
        String state,
        int attempts,
        String operationId,
        long dueAt,
        long token,
        long leaseUntil,
        String errorCode) { }
