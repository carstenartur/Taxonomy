package com.taxonomy.dto;

public class AnalysisRequest {

    private String businessText;
    private boolean includeArchitectureView;
    // Null means "use the live server-side runtime preference". Keeping this
    // nullable is essential: an explicit API value (including 0) must remain
    // distinguishable from an omitted field.
    private Integer maxArchitectureNodes;
    private String provider;
    private boolean resumable;
    private String continuationId;
    private Long continuationVersion;
    private String continuationAction;
    private String continuationQuestion;
    public boolean isResumable() { return resumable; }
    public void setResumable(boolean value) { resumable = value; }
    public String getContinuationId() { return continuationId; }
    public void setContinuationId(String value) { continuationId = value; }
    public Long getContinuationVersion() { return continuationVersion; }
    public void setContinuationVersion(Long value) { continuationVersion = value; }
    public String getContinuationAction() { return continuationAction; }
    public void setContinuationAction(String value) { continuationAction = value; }
    public String getContinuationQuestion() { return continuationQuestion; }
    public void setContinuationQuestion(String value) { continuationQuestion = value; }

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
