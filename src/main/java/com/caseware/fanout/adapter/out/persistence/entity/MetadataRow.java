package com.caseware.fanout.adapter.out.persistence.entity;

import org.springframework.data.relational.core.mapping.Table;

@Table("metadata")
public record MetadataRow(
        String firm,
        String engagement,
        long revision,
        String appliedVersion) { }
