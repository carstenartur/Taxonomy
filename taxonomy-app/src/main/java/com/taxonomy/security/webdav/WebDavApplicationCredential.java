package com.taxonomy.security.webdav;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Stored, revocable WebDAV-only application credential. The plaintext secret is never stored. */
@Entity
@Table(name = "webdav_application_credential")
public class WebDavApplicationCredential {

    @Id
    @Column(name = "credential_id", nullable = false, length = 24)
    private String credentialId;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(nullable = false, length = 160)
    private String description;

    @Column(name = "secret_hash", nullable = false, length = 100)
    private String secretHash;

    @Column(name = "read_allowed", nullable = false)
    private boolean readAllowed;

    @Column(name = "write_allowed", nullable = false)
    private boolean writeAllowed;

    @Column(nullable = false, length = 500)
    private String authorities;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public boolean isReadAllowed() { return readAllowed; }
    public void setReadAllowed(boolean readAllowed) { this.readAllowed = readAllowed; }
    public boolean isWriteAllowed() { return writeAllowed; }
    public void setWriteAllowed(boolean writeAllowed) { this.writeAllowed = writeAllowed; }
    public String getAuthorities() { return authorities; }
    public void setAuthorities(String authorities) { this.authorities = authorities; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public long getVersion() { return version; }
}
