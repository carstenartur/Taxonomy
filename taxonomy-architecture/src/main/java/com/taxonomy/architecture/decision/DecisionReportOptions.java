package com.taxonomy.architecture.decision;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/**
 * Presentation choices; these never change the recorded analysis scope.
 * An empty normalized root set means all analysis roots. Serialize that sentinel
 * as an omitted property so it is not confused with an invalid explicit empty selection.
 */
public record DecisionReportOptions(Profile profile,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Set<String> taxonomyRoots,
        Contents contents, TreeLayout treeLayout, Set<Section> sections) {
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
        treeLayout = treeLayout == null ? (profile == Profile.FULL ? TreeLayout.TABLE : TreeLayout.AUTO) : treeLayout;
        if (sections == null) sections = switch (profile) {
            case FULL -> EnumSet.allOf(Section.class);
            case STANDARD -> EnumSet.of(Section.SUMMARY, Section.TREE, Section.CHAPTERS, Section.ARCHITECTURE);
            case COMPACT -> EnumSet.of(Section.SUMMARY, Section.TREE);
        };
        sections = sections.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(sections));
    }

    public static DecisionReportOptions full() { return new DecisionReportOptions(null, null, null, null, null); }
    public boolean includes(Section section) { return sections.contains(section); }
}
