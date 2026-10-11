package com.taxonomy.backup.jobs;

import java.util.List;

/** Current authorized metadata for the native export form; it grants no future authority. */
public record BackupExportOptions(List<Selection> selections) {
    public BackupExportOptions { selections=List.copyOf(selections); }
    public boolean hasHistory() { return selections.stream().anyMatch(Selection::historyAvailable); }
    public record Selection(String key,String repositoryId,String workspaceId,boolean installation,String label,boolean historyAvailable) { }
}
