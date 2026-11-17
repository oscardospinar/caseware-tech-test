package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("messages")
public record MessageRow(
        @Id Long id,
        String taskId,
        long visibleAt,
        String receipt) { }
