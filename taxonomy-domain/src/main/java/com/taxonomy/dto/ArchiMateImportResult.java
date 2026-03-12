package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an ArchiMate XML import operation.
 * Describes how many elements and relations were imported or matched.
 */
public class ArchiMateImportResult {

    private int elementsImported;
    private int relationsImported;
    private int elementsMatched;
    private int elementsUnmatched;
    private List<String> notes = new ArrayList<>();

    public ArchiMateImportResult() {}

    public int getElementsImported() { return elementsImported; }
    public void setElementsImported(int elementsImported) { this.elementsImported = elementsImported; }

    public int getRelationsImported() { return relationsImported; }
    public void setRelationsImported(int relationsImported) { this.relationsImported = relationsImported; }

    public int getElementsMatched() { return elementsMatched; }
    public void setElementsMatched(int elementsMatched) { this.elementsMatched = elementsMatched; }

    public int getElementsUnmatched() { return elementsUnmatched; }
    public void setElementsUnmatched(int elementsUnmatched) { this.elementsUnmatched = elementsUnmatched; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
