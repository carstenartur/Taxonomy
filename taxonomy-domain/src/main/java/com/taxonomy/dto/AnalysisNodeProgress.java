package com.taxonomy.dto;

import java.util.List;

/** Catalogue coverage, independent of retained score previews and provider attempts. */
public record AnalysisNodeProgress(int total, int assessed, int excluded, int open,
                                   List<Taxonomy> taxonomies) {
    public AnalysisNodeProgress { taxonomies = List.copyOf(taxonomies); }
    public record Taxonomy(String root, String nameEn, String nameDe,
                           int total, int assessed, int excluded, int open,
                           Integer positive, Double allocatedPercent, Double rootPercent) {
        /** Old observations have unknown score metrics, rather than synthetic zeroes. */
        public Taxonomy(String root, String nameEn, String nameDe, int total, int assessed, int excluded, int open) {
            this(root, nameEn, nameDe, total, assessed, excluded, open, null, null, null);
        }
    }
}
