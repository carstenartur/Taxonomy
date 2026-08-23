package com.taxonomy.security.webdav;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebDavApplicationCredentialRepository
        extends JpaRepository<WebDavApplicationCredential, String> {

    Optional<WebDavApplicationCredential> findByCredentialIdAndUsername(
            String credentialId,
            String username);

    List<WebDavApplicationCredential> findAllByUsernameOrderByCreatedAtDesc(String username);
}
