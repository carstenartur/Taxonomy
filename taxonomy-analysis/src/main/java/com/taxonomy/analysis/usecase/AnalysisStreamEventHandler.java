package com.taxonomy.analysis.usecase;

@FunctionalInterface
public interface AnalysisStreamEventHandler {

    void handle(AnalysisStreamEvent event);
}
