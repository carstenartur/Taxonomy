package com.taxonomy.security.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Generated test-only issuer; no decoder or validation logic lives here. */
record KeycloakJwtTestIssuer(HttpServer server, RSAKey key, String origin,
                             AtomicReference<JwksResponse> jwks, AtomicInteger requests) implements AutoCloseable {
    record JwksResponse(int status, String body) { }

    static KeycloakJwtTestIssuer start() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("local-test").generate();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var jwks = new AtomicReference<>(new JwksResponse(200, new JWKSet(key.toPublicJWK()).toString()));
            var requests = new AtomicInteger();
            server.createContext("/keys", exchange -> {
                requests.incrementAndGet();
                var response = jwks.get();
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.status(), body.length);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                }
            });
            server.start();
            return new KeycloakJwtTestIssuer(server, key,
                    "http://127.0.0.1:" + server.getAddress().getPort(), jwks, requests);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot start test JWT issuer", failure);
        }
    }

    void publish(RSAKey... keys) {
        respond(200, new JWKSet(Arrays.stream(keys).map(key -> (JWK) key.toPublicJWK()).toList()).toString());
    }

    void respond(int status, String body) { jwks.set(new JwksResponse(status, body)); }

    int requestCount() { return requests.get(); }

    String token(String issuer, List<String> audiences, RSAKey signingKey,
                 Instant expiresAt, String authorizedParty) throws Exception {
        return token(issuer, audiences, signingKey, expiresAt, authorizedParty, List.of("ROLE_USER"), null);
    }

    private String token(String issuer, List<String> audiences, RSAKey signingKey,
                 Instant expiresAt, String authorizedParty, List<String> roles, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("audience-proof-person")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .claim("preferred_username", "audience-proof")
                .claim("realm_access", Map.of("roles", roles));
        if (expiresAt != null) { claims.expirationTime(Date.from(expiresAt)); }
        if (scope != null) { claims.claim("scope", scope); }
        if (audiences != null) { claims.audience(audiences); }
        if (authorizedParty != null) { claims.claim("azp", authorizedParty); }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    String token(List<String> audiences) throws Exception {
        return token(origin, audiences, key, Instant.now().plusSeconds(300), null);
    }

    String scopedToken(String audience, List<String> roles, String scope) throws Exception {
        return token(origin, List.of(audience), key, Instant.now().plusSeconds(300), null, roles, scope);
    }

    @Override public void close() { server.stop(0); }
}
