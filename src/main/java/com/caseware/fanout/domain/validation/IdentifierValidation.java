package com.caseware.fanout.domain.validation;

import com.caseware.fanout.domain.exception.InvalidIdentifierException;
/** Shared domain rules for required identifiers and their maximum length. */
public final class IdentifierValidation {
    private IdentifierValidation() { }

    public static void required(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new InvalidIdentifierException();
        }
    }
}
