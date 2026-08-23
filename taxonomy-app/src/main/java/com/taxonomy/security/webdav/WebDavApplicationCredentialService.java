package com.taxonomy.security.webdav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates, verifies and revokes user-bound WebDAV application credentials. */
@Service
public class WebDavApplicationCredentialService {

    public static final String TOKEN_PREFIX = "taxdav_";
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(30);
    public static final Duration MAX_LIFETIME = Duration.ofDays(90);

    private static final Logger log =
            LoggerFactory.getLogger(WebDavApplicationCredentialService.class);
    private static final Pattern TOKEN = Pattern.compile(
            "^taxdav_([0-9a-f]{24})_([A-Za-z0-9_-]{39})$");
    private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(5);
    private static final Set<String> RETAINED_ROLES =
            Set.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN");

    private final WebDavApplicationCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random;
    private final Clock clock;
    private final String dummyHash;

    @org.springframework.beans.factory.annotation.Autowired
    public WebDavApplicationCredentialService(
            WebDavApplicationCredentialRepository repository,
            PasswordEncoder passwordEncoder) {
        this(repository, passwordEncoder, new SecureRandom(), Clock.systemUTC());
    }

    WebDavApplicationCredentialService(
            WebDavApplicationCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            SecureRandom random,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dummyHash = passwordEncoder.encode("unused-webdav-dummy-secret");
    }

    @Transactional
    public CreatedCredential create(
            Authentication owner,
            String description,
            boolean readAllowed,
            boolean writeAllowed,
            Integer lifetimeDays) {
        requireAuthenticated(owner);
        if (!readAllowed && !writeAllowed) {
            throw new IllegalArgumentException("Select at least one WebDAV scope");
        }
        if (writeAllowed && !hasAuthority(owner, "ROLE_ADMIN")) {
            throw new SecurityException(
                    "Administrator authority is required for a write-capable WebDAV credential");
        }

        Duration lifetime = requestedLifetime(lifetimeDays);
        Instant now = clock.instant();
        String id = randomHex(12);
        // 29 random bytes retain 232 bits of entropy while keeping the complete
        // ASCII token below BCrypt's strict 72-byte input ceiling.
        String rawSecret = randomBase64Url(29);
        String token = TOKEN_PREFIX + id + "_" + rawSecret;

        WebDavApplicationCredential credential = new WebDavApplicationCredential();
        credential.setCredentialId(id);
        credential.setUsername(owner.getName());
        credential.setDescription(normalizeDescription(description));
        credential.setSecretHash(passwordEncoder.encode(token));
        credential.setReadAllowed(readAllowed || writeAllowed);
        credential.setWriteAllowed(writeAllowed);
        credential.setAuthorities(authorityString(owner.getAuthorities(), writeAllowed));
        credential.setCreatedAt(now);
        credential.setExpiresAt(now.plus(lifetime));
        repository.save(credential);

        log.info("audit.webdav_credential.created id={} user={} read={} write={} expires={}",
                id, owner.getName(), credential.isReadAllowed(), writeAllowed,
                credential.getExpiresAt());
        return new CreatedCredential(metadata(credential), token);
    }

    @Transactional(readOnly = true)
    public List<CredentialMetadata> list(Authentication owner) {
        requireAuthenticated(owner);
        return repository.findAllByUsernameOrderByCreatedAtDesc(owner.getName()).stream()
                .map(WebDavApplicationCredentialService::metadata)
                .toList();
    }

    @Transactional
    public void revoke(Authentication owner, String credentialId) {
        requireAuthenticated(owner);
        WebDavApplicationCredential credential = repository
                .findByCredentialIdAndUsername(credentialId, owner.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "WebDAV application credential was not found"));
        if (credential.getRevokedAt() == null) {
            credential.setRevokedAt(clock.instant());
            repository.save(credential);
            log.info("audit.webdav_credential.revoked id={} user={}",
                    credentialId, owner.getName());
        }
    }

    /**
     * Verify a Basic-auth password that uses the Taxonomy WebDAV token format.
     * Missing IDs still perform a BCrypt comparison to reduce identifier probing.
     */
    @Transactional
    public Optional<CredentialPrincipal> authenticate(String username, String token) {
        if (username == null || username.isBlank() || token == null) {
            passwordEncoder.matches(String.valueOf(token), dummyHash);
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(token);
        if (!matcher.matches()) {
            passwordEncoder.matches(token, dummyHash);
            return Optional.empty();
        }

        String id = matcher.group(1);
        Optional<WebDavApplicationCredential> found =
                repository.findByCredentialIdAndUsername(id, username);
        if (found.isEmpty()) {
            passwordEncoder.matches(token, dummyHash);
            return Optional.empty();
        }
        WebDavApplicationCredential credential = found.orElseThrow();
        Instant now = clock.instant();
        if (credential.getRevokedAt() != null
                || !credential.getExpiresAt().isAfter(now)
                || !passwordEncoder.matches(token, credential.getSecretHash())) {
            return Optional.empty();
        }

        updateLastUsedIfNeeded(credential, now);
        List<GrantedAuthority> authorities = parseAuthorities(credential.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_WEBDAV_APPLICATION"));
        if (credential.isReadAllowed()) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_template:read"));
        }
        if (credential.isWriteAllowed()) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_template:write"));
        }
        return Optional.of(new CredentialPrincipal(
                credential.getCredentialId(),
                credential.getUsername(),
                credential.isReadAllowed(),
                credential.isWriteAllowed(),
                List.copyOf(authorities)));
    }

    public static boolean isApplicationSecret(String value) {
        return value != null && value.startsWith(TOKEN_PREFIX);
    }

    private void updateLastUsedIfNeeded(
            WebDavApplicationCredential credential,
            Instant now) {
        Instant previous = credential.getLastUsedAt();
        if (previous != null && previous.plus(LAST_USED_WRITE_INTERVAL).isAfter(now)) {
            return;
        }
        credential.setLastUsedAt(now);
        try {
            repository.save(credential);
        } catch (OptimisticLockingFailureException concurrentUse) {
            log.debug("Concurrent WebDAV credential last-used update for {}",
                    credential.getCredentialId());
        }
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private String randomBase64Url(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Duration requestedLifetime(Integer days) {
        int effective = days == null ? (int) DEFAULT_LIFETIME.toDays() : days;
        if (effective < 1 || effective > MAX_LIFETIME.toDays()) {
            throw new IllegalArgumentException(
                    "WebDAV credential lifetime must be between 1 and "
                            + MAX_LIFETIME.toDays() + " days");
        }
        return Duration.ofDays(effective);
    }

    private static String normalizeDescription(String value) {
        String normalized = value == null || value.isBlank()
                ? "Microsoft Word WebDAV" : value.strip();
        normalized = normalized.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (!(codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF))) {
                throw new IllegalArgumentException(
                        "Credential description contains an invalid character");
            }
            offset += Character.charCount(codePoint);
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                    "Credential description must not exceed 160 characters");
        }
        return normalized;
    }

    private static String authorityString(
            Collection<? extends GrantedAuthority> authorities,
            boolean writeAllowed) {
        List<String> retained = authorities == null ? new ArrayList<>()
                : authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(RETAINED_ROLES::contains)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (retained.isEmpty()) {
            retained.add("ROLE_USER");
        }
        if (writeAllowed && !retained.contains("ROLE_ADMIN")) {
            throw new SecurityException("Administrator authority is required");
        }
        return String.join(",", retained);
    }

    private static List<GrantedAuthority> parseAuthorities(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(RETAINED_ROLES::contains)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new BadCredentialsException("Authentication is required");
        }
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private static CredentialMetadata metadata(WebDavApplicationCredential value) {
        List<String> scopes = value.isWriteAllowed()
                ? List.of("template:read", "template:write")
                : value.isReadAllowed() ? List.of("template:read") : List.of();
        return new CredentialMetadata(
                value.getCredentialId(),
                value.getDescription(),
                scopes,
                value.getCreatedAt(),
                value.getExpiresAt(),
                value.getLastUsedAt(),
                value.getRevokedAt());
    }

    public record CreatedCredential(CredentialMetadata credential, String secret) {
        public CreatedCredential {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(secret, "secret");
        }
    }

    public record CredentialMetadata(
            String id,
            String description,
            List<String> scopes,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt) {
        public boolean active(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }
    }

    public record CredentialPrincipal(
            String credentialId,
            String username,
            boolean readAllowed,
            boolean writeAllowed,
            List<GrantedAuthority> authorities) {
    }
}
