package com.caseware.fanout.domain.model;

/** Snapshot of publication planning progress; a null cursor starts or ends the inventory scan. */
public record Run(Publication publication, String cursor, boolean finished) { }
