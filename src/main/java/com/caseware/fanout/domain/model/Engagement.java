package com.caseware.fanout.domain.model;

import com.caseware.fanout.domain.validation.IdentifierValidation;

/** Regional identity of an engagement; contains metadata only, never engagement content. */
public record Engagement(String region, String firm, String id) {
    public Engagement {
        IdentifierValidation.required(region);
        IdentifierValidation.required(firm);
        IdentifierValidation.required(id);
    }
}
