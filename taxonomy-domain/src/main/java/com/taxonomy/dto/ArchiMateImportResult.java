package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an ArchiMate XML preview or import operation.
 *
 * <p>The counters deliberately distinguish parsing/matching from mutation so
 * clients can tell whether a model was understood, skipped as a duplicate, or
 * materialized into the active workspace.</p>
 */
public class ArchiMateImportResult {

    private int elementsImported;
    private int elementsMatched;
    private int elementsUnmatched;
    private int relationsParsed;
    private int relationsImported;
    private int relationsSkipped;
    private int relationsRejected;
    private boolean preview;
    private List<String> notes = new ArrayList<>();

    public ArchiMateImportResult() {
    }

    public int getElementsImported() { return elementsImported; }
    public void setElementsImported(int elementsImported) { this.elementsImported = elementsImported; }

    public int getElementsMatched() { return elementsMatched; }
    public void setElementsMatched(int elementsMatched) { this.elementsMatched = elementsMatched; }

    public int getElementsUnmatched() { return elementsUnmatched; }
    public void setElementsUnmatched(int elementsUnmatched) { this.elementsUnmatched = elementsUnmatched; }

    public int getRelationsParsed() { return relationsParsed; }
    public void setRelationsParsed(int relationsParsed) { this.relationsParsed = relationsParsed; }

    public int getRelationsImported() { return relationsImported; }
    public void setRelationsImported(int relationsImported) { this.relationsImported = relationsImported; }

    public int getRelationsSkipped() { return relationsSkipped; }
    public void setRelationsSkipped(int relationsSkipped) { this.relationsSkipped = relationsSkipped; }

    public int getRelationsRejected() { return relationsRejected; }
    public void setRelationsRejected(int relationsRejected) { this.relationsRejected = relationsRejected; }

    public boolean isPreview() { return preview; }
    public void setPreview(boolean preview) { this.preview = preview; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
