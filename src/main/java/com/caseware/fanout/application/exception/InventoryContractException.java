package com.caseware.fanout.application.exception;

public final class InventoryContractException extends ApplicationException {
    public InventoryContractException(String message) {
        super(ApplicationErrorCode.INVALID_INVENTORY_PAGE, message, null);
    }
}
