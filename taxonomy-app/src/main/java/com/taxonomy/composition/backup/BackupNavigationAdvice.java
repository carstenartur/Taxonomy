package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import com.taxonomy.backup.jobs.BackupCaptureSource;
import com.taxonomy.backup.runtime.BackupAccessPolicy;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Discover the explicitly enabled preview from the normal File menu using current stable-principal authority. */
@ControllerAdvice
@ConditionalOnProperty(name="taxonomy.backup.enabled",havingValue="true")
public class BackupNavigationAdvice {
    private final ObjectProvider<BackupCaptureSource> sources;private final PrincipalIdentityService identities;private final BackupAccessPolicy access;
    public BackupNavigationAdvice(ObjectProvider<BackupCaptureSource> sources,PrincipalIdentityService identities,BackupAccessPolicy access){this.sources=sources;this.identities=identities;this.access=access;}
    @ModelAttribute("portableBackupAvailable") boolean available(Authentication authentication) {
        if(authentication==null || sources.getIfAvailable()==null)return false;
        try {return access.hasCapability(identities.require(authentication),BackupCapability.EXPORT_CURRENT,new BackupScope.Installation());}
        catch(AccessDeniedException denied){return false;}
    }
}
