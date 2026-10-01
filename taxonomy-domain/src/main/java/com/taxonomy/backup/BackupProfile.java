package com.taxonomy.backup;

/** Profiles define scope and whether ancestors/audit payloads may be included. */
public enum BackupProfile {
    CURRENT_STATE(false, false), SELECTED_VERSION(false, false), REPOSITORY_HISTORY(true, false),
    INSTALLATION_CURRENT(false, true), INSTALLATION_FULL(true, true);

    private final boolean history;
    private final boolean installation;
    BackupProfile(boolean history, boolean installation) { this.history = history; this.installation = installation; }
    public boolean includesHistory() { return history; }
    public boolean isInstallation() { return installation; }
}
