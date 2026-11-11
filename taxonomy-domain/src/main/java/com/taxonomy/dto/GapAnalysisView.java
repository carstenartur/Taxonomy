package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/** Scoped obligation judgments. Absence is meaningful only within the identified complete snapshot. */
public class GapAnalysisView {
    private String businessText;
    private String profileId;
    private String profileVersion;
    private String metamodelVersion;
    private String repositoryId;
    private String workspaceId;
    private String workspaceScopeKey;
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String value) { workspaceId = value; }
    public String getWorkspaceScopeKey() { return workspaceScopeKey; }
    public void setWorkspaceScopeKey(String value) { workspaceScopeKey = value; }
    private String branch;
    private String commitId;
    private String evidenceAuthority;
    private EvidenceCompleteness evidenceCompleteness;
    private List<ObligationJudgment> judgments = new ArrayList<>();
    private int totalAnchors;
    private List<String> notes = new ArrayList<>();
    public String getBusinessText() { return businessText; }
    public void setBusinessText(String value) { businessText = value; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String value) { profileId = value; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String value) { profileVersion = value; }
    public String getMetamodelVersion() { return metamodelVersion; }
    public void setMetamodelVersion(String value) { metamodelVersion = value; }
    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String value) { repositoryId = value; }
    public String getBranch() { return branch; }
    public void setBranch(String value) { branch = value; }
    public String getCommitId() { return commitId; }
    public void setCommitId(String value) { commitId = value; }
    public String getEvidenceAuthority() { return evidenceAuthority; }
    public void setEvidenceAuthority(String value) { evidenceAuthority = value; }
    public EvidenceCompleteness getEvidenceCompleteness() { return evidenceCompleteness; }
    public void setEvidenceCompleteness(EvidenceCompleteness value) { evidenceCompleteness = value; }
    public List<ObligationJudgment> getJudgments() { return judgments; }
    public void setJudgments(List<ObligationJudgment> value) { judgments = List.copyOf(value); }
    public int getTotalAnchors() { return totalAnchors; }
    public void setTotalAnchors(int value) { totalAnchors = value; }
    public int getTotalGaps() { return (int) judgments.stream().filter(j -> j.status() == ObligationJudgment.Status.VIOLATED).count(); }
    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> value) { notes = value; }
}
