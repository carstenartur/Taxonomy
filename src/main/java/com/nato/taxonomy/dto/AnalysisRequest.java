package com.nato.taxonomy.dto;

public class AnalysisRequest {

    private String businessText;
    private boolean includeArchitectureView;
    private int maxArchitectureNodes = 20;

    public AnalysisRequest() {}

    public AnalysisRequest(String businessText) {
        this.businessText = businessText;
    }

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public boolean isIncludeArchitectureView() { return includeArchitectureView; }
    public void setIncludeArchitectureView(boolean includeArchitectureView) { this.includeArchitectureView = includeArchitectureView; }

    public int getMaxArchitectureNodes() { return maxArchitectureNodes; }
    public void setMaxArchitectureNodes(int maxArchitectureNodes) { this.maxArchitectureNodes = maxArchitectureNodes; }
}
