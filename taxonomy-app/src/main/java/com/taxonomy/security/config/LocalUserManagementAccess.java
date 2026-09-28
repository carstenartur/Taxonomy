package com.taxonomy.security.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Shared registration/navigation contract; an external provider never falls back to local administration. */
public final class LocalUserManagementAccess {
    public static final String PROFILE_EXPRESSION = "local-user-management & !keycloak";
    public static final String PROPERTY = "taxonomy.security.local-users-enabled";

    private LocalUserManagementAccess() {
    }

    public static boolean isEnabled(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PROFILE_EXPRESSION))
                && "true".equalsIgnoreCase(environment.getProperty(PROPERTY, "true"));
    }
}
