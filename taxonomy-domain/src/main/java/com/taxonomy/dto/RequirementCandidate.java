package com.taxonomy.dto;

/**
 * A requirement candidate extracted from a parsed document, awaiting user
 * review before entering the analysis workflow.
 */
public class RequirementCandidate {

    private int index;
    private String sectionHeading;
    private String text;
    private Integer pageNumber;
    private boolean selected;

    public RequirementCandidate() {}

    public RequirementCandidate(int index, String sectionHeading, String text, Integer pageNumber) {
        this.index = index;
        this.sectionHeading = sectionHeading;
        this.text = text;
        this.pageNumber = pageNumber;
        this.selected = true;
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public String getSectionHeading() { return sectionHeading; }
    public void setSectionHeading(String sectionHeading) { this.sectionHeading = sectionHeading; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
}
