package com.caseware.fanout.application.exception;

public final class InvalidConfigurationException extends ApplicationException {
    public InvalidConfigurationException(String message) {
        super(ApplicationErrorCode.INVALID_CONFIGURATION, message, null);
    }
}
