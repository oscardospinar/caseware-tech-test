package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("outbox")
public record OutboxRow(
        @Id String taskId,
        boolean published) { }
