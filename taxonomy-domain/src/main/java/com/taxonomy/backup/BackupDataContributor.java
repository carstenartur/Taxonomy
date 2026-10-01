package com.taxonomy.backup;

import java.util.List;
import java.util.Set;

/** Module-owned inventory contract, checked before a capture can be called complete. */
public interface BackupDataContributor extends BackupContributor {
    Set<String> categories();
    List<String> omissions(BackupProfile profile);
}
