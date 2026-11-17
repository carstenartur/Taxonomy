package com.taxonomy.reporting.api.decision;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.taxonomy.diagram.VisioDrawingOptions;

import java.util.*;

/**
 * Presentation choices; these never change the recorded analysis scope.
 * An empty normalized root set means all analysis roots. Serialize that sentinel
 * as an omitted property so it is not confused with an invalid explicit empty selection.
 */
public record DecisionReportOptions(Profile profile,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Set<String> taxonomyRoots,
        Contents contents, TreeLayout treeLayout, Set<Section> sections,
        Boolean showResponsibilities,
        @JsonInclude(JsonInclude.Include.NON_NULL) Visio visio) {
    public record Visio(VisioDrawingOptions.Mode mode, VisioDrawingOptions.Paper paper,
            Boolean appendix, Boolean shortReasons) {
        public Visio {
            if (mode == null) mode = VisioDrawingOptions.Mode.SCREEN;
            if (paper == null) paper = VisioDrawingOptions.Paper.A1;
            appendix = Boolean.TRUE.equals(appendix);
            shortReasons = Boolean.TRUE.equals(shortReasons);
        }

        public VisioDrawingOptions drawingOptions(boolean german) {
            return new VisioDrawingOptions(mode, paper, appendix, shortReasons, german);
        }
    }

    public DecisionReportOptions(Profile profile, Set<String> taxonomyRoots, Contents contents, TreeLayout treeLayout,
            Set<Section> sections, Boolean showResponsibilities) {
        this(profile, taxonomyRoots, contents, treeLayout, sections, showResponsibilities, null);
    }

    public DecisionReportOptions(Profile profile, Set<String> taxonomyRoots, Contents contents, TreeLayout treeLayout,
            Set<Section> sections) {
        this(profile, taxonomyRoots, contents, treeLayout, sections, null);
    }

    public enum Profile { FULL, STANDARD, COMPACT }
    public enum Contents { NONE, SHORT, FULL }
    public enum TreeLayout { TABLE, AUTO, A4_LANDSCAPE, A3_LANDSCAPE }
    public enum Section { TITLE_PAGE, SUMMARY, TREE, CHAPTERS, ARCHITECTURE, EVIDENCE }

    public DecisionReportOptions {
        profile = profile == null ? Profile.FULL : profile;
        if (taxonomyRoots != null && taxonomyRoots.isEmpty())
            throw new IllegalArgumentException("An explicit export selection must contain a taxonomy; omit taxonomyRoots for all analysis roots");
        var roots = new TreeSet<String>();
        if (taxonomyRoots != null) for (String root : taxonomyRoots) {
            if (root == null || root.isBlank() || !root.equals(root.strip()) || root.length() > 256)
                throw new IllegalArgumentException("Invalid export taxonomy root");
            roots.add(root);
        }
        if (roots.size() > 100) throw new IllegalArgumentException("Too many export taxonomy roots");
        taxonomyRoots = Collections.unmodifiableSet(roots);
        contents = contents == null ? (profile == Profile.FULL ? Contents.FULL : Contents.SHORT) : contents;
        treeLayout = treeLayout == null ? TreeLayout.AUTO : treeLayout;
        if (sections == null) sections = switch (profile) {
            case FULL -> EnumSet.allOf(Section.class);
            case STANDARD -> EnumSet.of(Section.SUMMARY, Section.TREE, Section.CHAPTERS, Section.ARCHITECTURE);
            case COMPACT -> EnumSet.of(Section.SUMMARY, Section.TREE);
        };
        sections = sections.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(sections));
    }

    public DecisionReportOptions forVisioDrawing() {
        if (visio != null) return this;
        return new DecisionReportOptions(profile, taxonomyRoots.isEmpty() ? null : taxonomyRoots,
                contents, treeLayout, sections, showResponsibilities, new Visio(null, null, false, false));
    }

    public Boolean showResponsibilities() {
        return showResponsibilities == null ? visio != null : showResponsibilities;
    }

    // An absent preference keeps older reports compact. In drawing requests an explicit
    // opt-out must survive replay, since drawings default to including recorded assignments.
    @JsonGetter("showResponsibilities")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean serializedResponsibilities() {
        return visio != null || Boolean.TRUE.equals(showResponsibilities) ? showResponsibilities : null;
    }

    public static DecisionReportOptions full() { return new DecisionReportOptions(null, null, null, null, null); }
    public boolean includes(Section section) { return sections.contains(section); }
}
