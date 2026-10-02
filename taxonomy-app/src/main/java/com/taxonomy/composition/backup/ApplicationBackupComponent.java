package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import com.taxonomy.preferences.backup.ApplicationConfigurationBackupContributor;
import com.taxonomy.preferences.backup.PreferencesBackupContributor;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.provenance.backup.ApplicationBackupContributor;
import com.taxonomy.security.backup.IdentityBackupContributor;
import org.springframework.core.env.ConfigurableEnvironment;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Explicit assembly only; complete fenced capture and reference selectors remain caller-owned. */
public final class ApplicationBackupComponent {
    private ApplicationBackupComponent() { }

    public static BackupDataContributor create(DataSource database, BackupSourceReference.Selector sources,
                                              BackupPrincipalSelector principals, Path contentRoot,
                                              PreferencesGitRepository preferences, ConfigurableEnvironment environment) throws IOException {
        return new CompositeBackupDataContributor(new BackupComponentId("application"), 1, BackupCoverageInventory.load(), List.of(
                new ApplicationBackupContributor(database, sources, contentRoot),
                new IdentityBackupContributor(database, principals),
                new PreferencesBackupContributor(preferences),
                new ApplicationConfigurationBackupContributor(environment)));
    }
}
