package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("firms")
public record FirmRow(
        @Id String firm,
        long lastTurn) { }
