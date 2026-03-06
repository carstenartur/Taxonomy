package com.nato.taxonomy.dto;

public class AnalysisRequest {

    private String businessText;
    private boolean includeArchitectureView;

    public AnalysisRequest() {}

    public AnalysisRequest(String businessText) {
        this.businessText = businessText;
    }

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public boolean isIncludeArchitectureView() { return includeArchitectureView; }
    public void setIncludeArchitectureView(boolean includeArchitectureView) { this.includeArchitectureView = includeArchitectureView; }
}
