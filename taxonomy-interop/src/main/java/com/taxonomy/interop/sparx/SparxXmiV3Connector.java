package com.taxonomy.interop.sparx;

import org.springframework.stereotype.Component;

/** Explicit Taxonomy-semantic XMI profile; existing v1/v2 connections retain their own contracts. */
@Component
public final class SparxXmiV3Connector extends SparxXmiConnector {
    public SparxXmiV3Connector() { super("3"); }
}
