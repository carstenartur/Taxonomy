package com.taxonomy.visio;

/** One explicitly mapped, omitted or unsupported aspect of the visual handoff. */
public record VisioLoss(String scope, String id, String field, String kind, String rationale) { }
