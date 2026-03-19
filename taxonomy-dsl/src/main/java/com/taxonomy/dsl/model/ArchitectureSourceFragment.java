package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a traceable fragment within a source version.
 *
 * <p>Corresponds to a DSL {@code sourceFragment} block.
 */
public class ArchitectureSourceFragment {

    private String id;
    private String sourceVersionId;
    private String sectionPath;
    private String paragraphRef;
    private Integer pageFrom;
    private Integer pageTo;
    private String text;
    private String fragmentHash;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureSourceFragment() {}

    public ArchitectureSourceFragment(String id, String sourceVersionId) {
        this.id = id;
        this.sourceVersionId = sourceVersionId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(String sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    public String getParagraphRef() { return paragraphRef; }
    public void setParagraphRef(String paragraphRef) { this.paragraphRef = paragraphRef; }

    public Integer getPageFrom() { return pageFrom; }
    public void setPageFrom(Integer pageFrom) { this.pageFrom = pageFrom; }

    public Integer getPageTo() { return pageTo; }
    public void setPageTo(Integer pageTo) { this.pageTo = pageTo; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getFragmentHash() { return fragmentHash; }
    public void setFragmentHash(String fragmentHash) { this.fragmentHash = fragmentHash; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
