package com.taxonomy.backup;

/** Independent capabilities; repository/workspace visibility is always checked as well. */
public enum BackupCapability {
    EXPORT_CURRENT, EXPORT_HISTORY, EXPORT_INSTALLATION, DOWNLOAD_BACKUP, RESTORE_INSTALLATION,
    IMPORT_REPOSITORY, SUBMIT_GIT_CHANGES, INTEGRATE_GIT_CHANGES, INCLUDE_SECRETS
}
