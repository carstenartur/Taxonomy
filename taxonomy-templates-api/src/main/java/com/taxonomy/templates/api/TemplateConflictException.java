package com.taxonomy.templates.api;

import java.io.IOException;

/** Public, repository-independent template contract. */
public final class TemplateConflictException extends IOException {
    private final String expectedHead;
    private final String actualHead;

    public TemplateConflictException(String expectedHead, String actualHead) {
        super(expectedHead == null
                ? "Document template already exists"
                        + (actualHead == null ? "" : "; current version is " + actualHead)
                : "Document template changed concurrently"
                        + (actualHead == null ? "; it no longer exists"
                                : "; current version is " + actualHead));
        this.expectedHead = expectedHead;
        this.actualHead = actualHead;
    }

    public String expectedHead() {
        return expectedHead;
    }

    public String actualHead() {
        return actualHead;
    }
}
