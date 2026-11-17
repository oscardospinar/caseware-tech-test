package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Id;

@Table("control")
public record ControlRow(
        @Id Integer id,
        String region,
        String config,
        long nextStart,
        int failures,
        long openUntil,
        String probeTask,
        long turn) { }
