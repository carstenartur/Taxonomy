package com.taxonomy.workspace.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

/** Operator-managed keys live outside Git and backups; no production key generation. */
@ConfigurationProperties(prefix = "taxonomy.git.signing")
public class GitSigningProperties {
    private boolean enabled;
    private String secretKeyFile = "";
    private String passphrase = "";
    private List<String> trustedPublicKeyFiles = new ArrayList<>();
    private String trustedPublicKeyDirectory = "";
    private boolean requireSignatures;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSecretKeyFile() { return secretKeyFile; }
    public void setSecretKeyFile(String secretKeyFile) { this.secretKeyFile = secretKeyFile; }
    // No generated toString: configuration includes a secret passphrase.
    public String getPassphrase() { return passphrase; }
    public void setPassphrase(String passphrase) { this.passphrase = passphrase; }
    public List<String> getTrustedPublicKeyFiles() { return trustedPublicKeyFiles; }
    public void setTrustedPublicKeyFiles(List<String> files) { this.trustedPublicKeyFiles = files; }
    public String getTrustedPublicKeyDirectory() { return trustedPublicKeyDirectory; }
    public void setTrustedPublicKeyDirectory(String directory) { this.trustedPublicKeyDirectory = directory; }
    public boolean isRequireSignatures() { return requireSignatures; }
    public void setRequireSignatures(boolean requireSignatures) { this.requireSignatures = requireSignatures; }
}
