package com.nato.taxonomy.dto;

public class AnalysisRequest {

    private String businessText;

    public AnalysisRequest() {}

    public AnalysisRequest(String businessText) {
        this.businessText = businessText;
    }

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }
}
