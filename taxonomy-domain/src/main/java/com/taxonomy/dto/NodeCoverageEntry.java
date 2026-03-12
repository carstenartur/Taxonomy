package com.taxonomy.dto;

/**
 * A single entry in a coverage summary, pairing a node code with how many
 * requirements cover it.
 */
public record NodeCoverageEntry(String nodeCode, int requirementCount) {}
