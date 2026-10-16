package com.taxonomy.dto;

public class RequirementAnchor {

    private String nodeCode;
    private double directScore;
    private String reason;

    public RequirementAnchor() {}

    public RequirementAnchor(String nodeCode, double directScore, String reason) {
        this.nodeCode = nodeCode;
        this.directScore = directScore;
        this.reason = reason;
    }

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public double getDirectScore() { return directScore; }
    public void setDirectScore(double directScore) { this.directScore = directScore; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
