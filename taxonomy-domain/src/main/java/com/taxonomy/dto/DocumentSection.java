package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a hierarchical section within a parsed document.
 *
 * <p>Builds a tree structure from headings detected during document parsing.
 * Each section can have children (subsections) and paragraphs (text content).
 * The {@code sectionPath} is a human-readable breadcrumb like
 * {@code "§ 3 Datenschutz > Abs. 2 Verarbeitung"}.
 */
public class DocumentSection {

    private Long id;
    private Long parentId;
    private int level;
    private String heading;
    private String sectionPath;
    private List<DocumentSection> children = new ArrayList<>();
    private List<String> paragraphs = new ArrayList<>();
    private Integer pageFrom;
    private Integer pageTo;

    public DocumentSection() {}

    public DocumentSection(int level, String heading) {
        this.level = level;
        this.heading = heading;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    public List<DocumentSection> getChildren() { return children; }
    public void setChildren(List<DocumentSection> children) { this.children = children; }

    public List<String> getParagraphs() { return paragraphs; }
    public void setParagraphs(List<String> paragraphs) { this.paragraphs = paragraphs; }

    public Integer getPageFrom() { return pageFrom; }
    public void setPageFrom(Integer pageFrom) { this.pageFrom = pageFrom; }

    public Integer getPageTo() { return pageTo; }
    public void setPageTo(Integer pageTo) { this.pageTo = pageTo; }
}
