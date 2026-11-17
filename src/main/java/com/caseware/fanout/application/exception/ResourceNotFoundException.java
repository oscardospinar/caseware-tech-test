package com.caseware.fanout.application.exception;

public final class ResourceNotFoundException extends ApplicationException {
    public enum Resource { TASK, PUBLICATION }

    private final Resource resource;

    public ResourceNotFoundException(Resource resource) {
        super(ApplicationErrorCode.RESOURCE_NOT_FOUND, "Required resource does not exist: " + resource.name(), null);
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }
}
