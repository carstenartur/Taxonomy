package com.taxonomy.interop.oslc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Map;

/** Administrator-owned endpoints. Connections store only a profile key; credentials never enter exchange DTOs. */
@Component
@ConfigurationProperties(prefix = "taxonomy.integrations")
public class OslcRemoteProfiles {
    private Map<String, RemoteProfile> remotes = Map.of();
    public Map<String, RemoteProfile> getRemotes() { return remotes; }
    public void setRemotes(Map<String, RemoteProfile> value) { remotes = value == null ? Map.of() : Map.copyOf(value); }
    public record RemoteProfile(String repositoryId, String organizationId, URI baseUri,
                                String credentialEnvironmentVariable, boolean allowPrivateNetworks, boolean allowInsecureHttp) {}
}
