package com.taxonomy.security.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.util.List;
import java.util.Map;

/** Generated test-only issuer; no decoder or validation logic lives here. */
record KeycloakJwtTestIssuer(HttpServer server, RSAKey key, String origin) implements AutoCloseable {
    static KeycloakJwtTestIssuer start() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("local-test").generate();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/keys", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var output = exchange.getResponseBody()) { output.write(keys); }
            });
            server.start();
            return new KeycloakJwtTestIssuer(server, key,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot start test JWT issuer", failure);
        }
    }

    String token(String issuer, List<String> audiences, RSAKey signingKey,
                 Instant expiresAt, String authorizedParty) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("audience-proof-person")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiresAt))
                .claim("preferred_username", "audience-proof")
                .claim("realm_access", Map.of("roles", List.of("ROLE_USER")));
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

    @Override public void close() { server.stop(0); }
}
