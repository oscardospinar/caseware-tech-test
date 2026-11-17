package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.validation.IdentifierValidation;

/** Immutable identity and target of a template publication. */
public record Publication(String id, String region, String product, String target) {
    public Publication {
        IdentifierValidation.required(id);
        IdentifierValidation.required(region);
        IdentifierValidation.required(product);
        IdentifierValidation.required(target);
    }
}
