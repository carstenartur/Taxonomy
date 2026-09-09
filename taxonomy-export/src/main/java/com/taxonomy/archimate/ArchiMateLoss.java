package com.taxonomy.archimate;

/** One explicitly transformed or omitted field, bound to its original identity. */
public record ArchiMateLoss(String scope, String id, String field, String kind, String rationale) { }
