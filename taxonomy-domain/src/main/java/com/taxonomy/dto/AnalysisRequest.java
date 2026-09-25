package com.taxonomy.dto;

public class AnalysisRequest {

    private String businessText;
    private boolean includeArchitectureView;
    // Null means "use the live server-side runtime preference". Keeping this
    // nullable is essential: an explicit API value (including 0) must remain
    // distinguishable from an omitted field.
    private Integer maxArchitectureNodes;
    private String provider;

    public AnalysisRequest() {}

    public AnalysisRequest(String businessText) {
        this.businessText = businessText;
    }

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public boolean isIncludeArchitectureView() { return includeArchitectureView; }
    public void setIncludeArchitectureView(boolean includeArchitectureView) { this.includeArchitectureView = includeArchitectureView; }

    public Integer getMaxArchitectureNodes() { return maxArchitectureNodes; }
    public void setMaxArchitectureNodes(Integer maxArchitectureNodes) { this.maxArchitectureNodes = maxArchitectureNodes; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
