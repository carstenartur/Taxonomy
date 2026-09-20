package com.taxonomy.interop.sparx;

import org.springframework.stereotype.Component;

/** Explicit immutable v2 registration; v1 connections never migrate implicitly. */
@Component
public final class SparxXmiV2Connector extends SparxXmiConnector {
    public SparxXmiV2Connector() { super("2"); }
}
