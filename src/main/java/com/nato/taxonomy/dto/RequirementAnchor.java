package com.nato.taxonomy.dto;

public class RequirementAnchor {

    private String nodeCode;
    private int directScore;
    private String reason;

    public RequirementAnchor() {}

    public RequirementAnchor(String nodeCode, int directScore, String reason) {
        this.nodeCode = nodeCode;
        this.directScore = directScore;
        this.reason = reason;
    }

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public int getDirectScore() { return directScore; }
    public void setDirectScore(int directScore) { this.directScore = directScore; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
