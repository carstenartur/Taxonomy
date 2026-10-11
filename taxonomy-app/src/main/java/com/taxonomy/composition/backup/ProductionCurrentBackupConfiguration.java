package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import com.taxonomy.backup.jobs.BackupCaptureSource;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.templates.DocumentTemplateGitRepository;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.ConfigurableEnvironment;

/** Optional host bridge: every durable data owner is required before the production export path exists. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="taxonomy.backup.enabled",havingValue="true")
@ConditionalOnFeature({"analysis","architecture","portfolio","interop","templates"})
public class ProductionCurrentBackupConfiguration {
    @Bean BackupCaptureSource productionCurrentBackupSource(DataSource database,EntityManagerFactory entities,
            BackupMaintenanceLease fence,BackupAuthorizationService authorization,BackupAccessPolicy access,
            PrincipalIdentityService identities,PreferencesGitRepository preferences,DocumentTemplateGitRepository templates,
            ConfigurableEnvironment environment,BackupFeaturePrerequisites prerequisites,List<BackupDataContributor> additionalContributors,
            org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.GitProperties> gitProperties) {
        return new ProductionCurrentBackupSource(database,entities,fence,authorization,access,identities,preferences,templates,
                environment,prerequisites,additionalContributors,gitProperties.getIfAvailable()==null?"unknown":gitProperties.getIfAvailable().getCommitId());
    }
}
