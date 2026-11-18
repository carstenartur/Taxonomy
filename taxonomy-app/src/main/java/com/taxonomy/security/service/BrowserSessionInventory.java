package com.taxonomy.security.service;

import com.taxonomy.security.model.StablePrincipal;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A read-only, bounded projection of the actual local Spring Security registry. */
@Service
public class BrowserSessionInventory {
    static final int MAX_ROWS = 200;
    static final int MAX_DISPLAY_NAME_LENGTH = 256;
    private final SessionRegistry registry;

    public BrowserSessionInventory(SessionRegistry registry) {
        this.registry = registry;
    }

    public Snapshot snapshot() {
        Map<Identity, UserSessions> users = new LinkedHashMap<>();
        int totalSessions = 0;
        int unidentifiedSessions = 0;
        for (Object principal : registry.getAllPrincipals()) {
            List<SessionInformation> sessions = registry.getAllSessions(principal, false);
            if (sessions.isEmpty()) continue;
            totalSessions += sessions.size();
            Identity identity = identity(principal);
            if (identity == null) {
                unidentifiedSessions += sessions.size();
                continue;
            }
            var mostRecent = sessions.stream().max(Comparator.comparing(SessionInformation::getLastRequest)).orElseThrow();
            Instant lastRequest = mostRecent.getLastRequest().toInstant();
            String displayName = displayName(mostRecent.getPrincipal());
            UserSessions previous = users.get(identity);
            int count = sessions.size();
            if (previous != null) {
                count += previous.sessionCount();
                if (previous.lastRequest().isAfter(lastRequest)) {
                    lastRequest = previous.lastRequest();
                    displayName = previous.username();
                }
            }
            users.put(identity, new UserSessions(displayName, identity.kind(), count, lastRequest));
        }
        List<UserSessions> rows = users.values().stream()
                .sorted(Comparator.comparing(UserSessions::username)
                        .thenComparing(UserSessions::authenticationType))
                .limit(MAX_ROWS).toList();
        return new Snapshot(Instant.now(), "LOCAL_INSTANCE", users.size(), totalSessions,
                unidentifiedSessions, users.size() > rows.size(), rows);
    }

    private static Identity identity(Object principal) {
        if (principal instanceof OidcUser oidc) {
            if (oidc.getIssuer() == null || oidc.getSubject() == null) return null;
            return new Identity("OIDC", new OidcIdentity(oidc.getIssuer().toString(), oidc.getSubject()));
        }
        if (principal instanceof UserDetails user) {
            if (user instanceof StablePrincipal stable) {
                return new Identity("LOCAL", stable.principalId());
            }
            return new Identity("LOCAL", user.getUsername());
        }
        return null; // Never fall back to arbitrary principal.toString().
    }

    private static String displayName(Object principal) {
        String name;
        if (principal instanceof OidcUser oidc) {
            name = oidc.getPreferredUsername();
            if (name == null || name.isBlank()) name = oidc.getSubject();
        } else {
            name = ((UserDetails) principal).getUsername();
        }
        return name.length() <= MAX_DISPLAY_NAME_LENGTH
                ? name
                : name.substring(0, MAX_DISPLAY_NAME_LENGTH - 1) + "…";
    }

    private record Identity(String kind, Object key) { }
    private record OidcIdentity(String issuer, String subject) { }
    public record UserSessions(String username, String authenticationType, int sessionCount, Instant lastRequest) { }
    public record Snapshot(Instant timestamp, String scope, int userCount, int sessionCount,
                           int unidentifiedSessionCount, boolean truncated, List<UserSessions> users) { }
}
