package com.taxonomy.dto;

/**
 * Represents a traceable fragment (e.g. paragraph, section, page range) within
 * a {@link SourceVersionDto}.
 */
public class SourceFragmentDto {

    private Long id;
    private Long sourceVersionId;
    private String sectionPath;
    private String paragraphRef;
    private Integer pageFrom;
    private Integer pageTo;
    private String fragmentText;
    private String fragmentHash;

    public SourceFragmentDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    public String getParagraphRef() { return paragraphRef; }
    public void setParagraphRef(String paragraphRef) { this.paragraphRef = paragraphRef; }

    public Integer getPageFrom() { return pageFrom; }
    public void setPageFrom(Integer pageFrom) { this.pageFrom = pageFrom; }

    public Integer getPageTo() { return pageTo; }
    public void setPageTo(Integer pageTo) { this.pageTo = pageTo; }

    public String getFragmentText() { return fragmentText; }
    public void setFragmentText(String fragmentText) { this.fragmentText = fragmentText; }

    public String getFragmentHash() { return fragmentHash; }
    public void setFragmentHash(String fragmentHash) { this.fragmentHash = fragmentHash; }
}
