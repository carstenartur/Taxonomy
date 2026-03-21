package com.taxonomy.provenance.model;

import jakarta.persistence.*;

/**
 * A traceable fragment (section, paragraph, page range) within a
 * {@link SourceVersion}.
 */
@Entity
@Table(name = "source_fragment")
public class SourceFragment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_version_id", nullable = false)
    private SourceVersion sourceVersion;

    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "paragraph_ref", length = 100)
    private String paragraphRef;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(name = "fragment_text", length = 4000)
    private String fragmentText;

    @Column(name = "fragment_hash", length = 128)
    private String fragmentHash;

    @Column(name = "parent_fragment_id")
    private Long parentFragmentId;

    @Column(name = "chunk_level")
    private Integer chunkLevel;

    protected SourceFragment() {}

    public SourceFragment(SourceVersion sourceVersion, String fragmentText) {
        this.sourceVersion = sourceVersion;
        this.fragmentText = fragmentText;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SourceVersion getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(SourceVersion sourceVersion) { this.sourceVersion = sourceVersion; }

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

    public Long getParentFragmentId() { return parentFragmentId; }
    public void setParentFragmentId(Long parentFragmentId) { this.parentFragmentId = parentFragmentId; }

    public Integer getChunkLevel() { return chunkLevel; }
    public void setChunkLevel(Integer chunkLevel) { this.chunkLevel = chunkLevel; }
}
