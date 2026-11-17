package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("runs")
public record RunRow(
        @Id String id,
        String pubId,
        String product,
        String target,
        String cursorValue,
        boolean finished,
        boolean active) { }
