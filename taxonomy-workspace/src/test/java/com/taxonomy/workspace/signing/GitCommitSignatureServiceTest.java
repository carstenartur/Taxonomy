package com.taxonomy.workspace.signing;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.workspace.signing.SigningTestKeys.armor;
import static com.taxonomy.workspace.signing.SigningTestKeys.generateKeys;

class GitCommitSignatureServiceTest {
    @TempDir Path directory;
    private static PGPKeyRingGenerator keys;
    private static PGPKeyRingGenerator otherKeys;

    @BeforeAll static void generateTestKeys() throws Exception {
        keys = generateKeys("Operator <operator@example.test>");
        otherKeys = generateKeys("External <external@example.test>");
    }

    @Test void signsStandardGitCommitAndVerifiesWithFingerprint() throws Exception {
        var service = configured(keys, true);
        var commit = commit();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        try {
            service.sign(commit, "personal/alice", "draft");
        } finally { SecurityContextHolder.clearContext(); }
        var result = service.verify(commit.build());
        assertThat(result.status()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(result.signer()).isEqualTo("Operator <operator@example.test>");
        assertThat(result.fingerprint()).isEqualTo(java.util.HexFormat.of().withUpperCase()
                .formatHex(keys.generatePublicKeyRing().getPublicKey().getFingerprint()));
        assertThat(result.serviceSignature()).isTrue();
        assertThat(new String(commit.build(), StandardCharsets.UTF_8)).contains("gpgsig -----BEGIN PGP SIGNATURE-----");
        service.requireAcceptable(commit.build());
    }

    @Test void signatureCreationTimeUsesCommitterTimeIncludingOnRetries() throws Exception {
        var service = configured(keys, true);
        var identity = new PersonIdent("alice", "alice@example.test", Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                java.time.ZoneOffset.UTC);
        for (int attempt = 0; attempt < 2; attempt++) {
            var commit = commit(); commit.setCommitter(identity);
            service.sign(commit, "project", "main");
            var factory = new PGPObjectFactory(PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(
                    commit.getGpgSignature().toExternalString().getBytes(StandardCharsets.US_ASCII))),
                    new JcaKeyFingerprintCalculator());
            var signatures = (PGPSignatureList) factory.nextObject();
            assertThat(signatures.get(0).getCreationTime().toInstant()).isEqualTo(identity.getWhenAsInstant());
        }
    }

    @Test void signatureCreationTimeCannotPredateRotatedSigningKey() throws Exception {
        var service = configured(keys, true);
        var commit = commit();
        commit.setCommitter(new PersonIdent("alice", "alice@example.test", Instant.parse("2020-01-02T03:04:05Z"),
                java.time.ZoneOffset.UTC));
        service.sign(commit, "project", "main");
        var factory = new PGPObjectFactory(PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(
                commit.getGpgSignature().toExternalString().getBytes(StandardCharsets.US_ASCII))),
                new JcaKeyFingerprintCalculator());
        var signatures = (PGPSignatureList) factory.nextObject();
        assertThat(signatures.get(0).getCreationTime()).isEqualTo(keys.generatePublicKeyRing().getPublicKey().getCreationTime());
    }

    @Test void tamperedPayloadAndSignatureAreRejected() throws Exception {
        var service = configured(keys, true);
        var commit = commit(); service.sign(commit, "project", "main");
        var tampered = new String(commit.build(), StandardCharsets.UTF_8).replace("model change", "model attack").getBytes(StandardCharsets.UTF_8);
        assertThat(service.verify(tampered).status()).isEqualTo(SignatureStatus.INVALID);
        assertThatThrownBy(() -> service.requireAcceptable(tampered)).isInstanceOf(IOException.class);
        var signature = commit.getGpgSignature().toExternalString();
        commit.setGpgSignature(new org.eclipse.jgit.lib.GpgSignature(signature.replace("BEGIN PGP", "BROKEN PGP").getBytes(StandardCharsets.US_ASCII)));
        assertThat(service.verify(commit.build()).status()).isNotEqualTo(SignatureStatus.VERIFIED);
    }

    @Test void unknownKeyIsUntrustedAndDefaultPolicyRejectsIt() throws Exception {
        var external = configured(otherKeys, true);
        var commit = commit(); external.sign(commit, "external", "main");
        var local = configured(keys, true);
        assertThat(local.verify(commit.build()).status()).isEqualTo(SignatureStatus.UNTRUSTED);
        assertThatThrownBy(() -> local.requireAcceptable(commit.build())).isInstanceOf(IOException.class);
        assertThat(GitCommitSignatureService.disabled().verify(commit.build()).status()).isEqualTo(SignatureStatus.UNTRUSTED);
    }

    @Test void unsignedLegacyAllowedUnlessRequired() throws Exception {
        var legacy = GitCommitSignatureService.disabled();
        assertThat(legacy.verify(commit().build()).status()).isEqualTo(SignatureStatus.UNSIGNED);
        legacy.requireAcceptable(commit().build());
        var properties = new GitSigningProperties(); properties.setRequireSignatures(true);
        var strict = new GitCommitSignatureService(properties);
        assertThatThrownBy(() -> strict.requireAcceptable(commit().build())).isInstanceOf(IOException.class);
    }

    @Test void enabledSigningRequiresUsableKeyAndPassphrase() throws Exception {
        var missing = new GitSigningProperties(); missing.setEnabled(true);
        assertThatThrownBy(() -> new GitCommitSignatureService(missing)).isInstanceOf(IOException.class);
        var properties = properties(keys, true); properties.setPassphrase("incorrect");
        assertThatThrownBy(() -> new GitCommitSignatureService(properties)).isInstanceOf(IOException.class)
                .hasMessageNotContaining("incorrect").hasMessageNotContaining("test-only");
        properties.setSecretKeyFile(directory.resolve("not-a-key").toString());
        Files.writeString(Path.of(properties.getSecretKeyFile()), "not a private key");
        assertThatThrownBy(() -> new GitCommitSignatureService(properties)).isInstanceOf(IOException.class);
    }

    @Test void verifierOnlyTrustStoreSupportsExternalOperatorAndRotation() throws Exception {
        var external = configured(otherKeys, true);
        var commit = commit(); external.sign(commit, "external", "main");
        var properties = new GitSigningProperties();
        var publicFile = directory.resolve("external.asc");
        Files.write(publicFile, armor(otherKeys.generatePublicKeyRing().getEncoded()));
        properties.setTrustedPublicKeyFiles(List.of(publicFile.toString()));
        var verifier = new GitCommitSignatureService(properties);
        assertThat(verifier.isSigningEnabled()).isFalse();
        assertThat(verifier.verify(commit.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(verifier.verify(commit.build()).serviceSignature()).isFalse();
        var unsigned = commit(); verifier.sign(unsigned, "project", "main");
        assertThat(verifier.verify(unsigned.build()).status()).isEqualTo(SignatureStatus.UNSIGNED);
    }

    @Test void malformedDuplicateUnsupportedAndOversizedSignaturesFailClosed() throws Exception {
        var service = configured(keys, true);
        var commit = commit(); service.sign(commit, "project", "main");
        var raw = new String(commit.build(), StandardCharsets.UTF_8);
        var duplicate = raw.replace("\n\nmodel change", "\ngpgsig bad\n\nmodel change").getBytes(StandardCharsets.UTF_8);
        assertThat(service.verify(duplicate).status()).isEqualTo(SignatureStatus.INVALID);
        assertThat(service.verify("bad object".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(SignatureStatus.INVALID);
        commit.setGpgSignature(new org.eclipse.jgit.lib.GpgSignature("-----BEGIN SSH SIGNATURE-----\nxyz\n-----END SSH SIGNATURE-----".getBytes(StandardCharsets.US_ASCII)));
        assertThat(service.verify(commit.build()).status()).isEqualTo(SignatureStatus.UNSUPPORTED);
        assertThat(service.verify(new byte[16 * 1024 * 1024 + 1]).status()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test void expiredDetachedSignatureIsInvalidEvenWhenKeyAndCryptoAreValid() throws Exception {
        var packets = new PGPSignatureSubpacketGenerator();
        packets.setSignatureCreationTime(false, java.util.Date.from(Instant.now().minusSeconds(30)));
        packets.setSignatureExpirationTime(false, 1);
        var commit = commit(); signWithSubpackets(commit, packets.generate());
        assertThat(configured(keys, true).verify(commit.build()).status()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test void unknownCriticalNotationIsUnsupportedEvenWithTrustedValidCrypto() throws Exception {
        var packets = new PGPSignatureSubpacketGenerator();
        packets.addNotationData(true, true, "unknown@example.test", "semantics not implemented");
        var commit = commit(); signWithSubpackets(commit, packets.generate());
        assertThat(configured(keys, true).verify(commit.build()).status()).isEqualTo(SignatureStatus.UNSUPPORTED);
    }

    @Test void certificationOnlyKeyCannotSignModelCommits() throws Exception {
        var packets = new PGPSignatureSubpacketGenerator();
        packets.setKeyFlags(false, org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER);
        var certificationOnlyKeys = generateKeys("Certification <certify@example.test>", packets.generate());
        var properties = properties(certificationOnlyKeys, true);
        assertThatThrownBy(() -> new GitCommitSignatureService(properties)).isInstanceOf(IOException.class);
    }

    @Test void expiredSigningKeyFailsStartup() throws Exception {
        var packets = new PGPSignatureSubpacketGenerator(); packets.setKeyExpirationTime(false, 1);
        var expiredKeys = generateKeys("Expired <expired@example.test>", packets.generate());
        var properties = properties(expiredKeys, true);
        assertThatThrownBy(() -> new GitCommitSignatureService(properties)).isInstanceOf(IOException.class);
    }

    @Test void revokedTrustedKeyRejectsPreviouslyValidSignature() throws Exception {
        var signer = configured(keys, true);
        var commit = commit(); signer.sign(commit, "project", "main");
        var ring = keys.generatePublicKeyRing();
        var file = directory.resolve("revoked.asc");
        Files.write(file, armor(revokedRing(keys).getEncoded()));
        var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(List.of(file.toString()));
        var verifier = new GitCommitSignatureService(properties);
        assertThat(verifier.verify(commit.build()).status()).isEqualTo(SignatureStatus.INVALID);
        assertThatThrownBy(() -> verifier.requireAcceptable(commit.build())).isInstanceOf(IOException.class);
    }

    @Test void revocationDominatesStalePublicCopyInEitherTrustFileOrder() throws Exception {
        var signer = configured(keys, true);
        var commit = commit(); signer.sign(commit, "project", "main");
        var stale = directory.resolve("stale.asc");
        var revoked = directory.resolve("revoked.asc");
        Files.write(stale, armor(keys.generatePublicKeyRing().getEncoded()));
        Files.write(revoked, armor(revokedRing(keys).getEncoded()));
        for (var files : List.of(List.of(stale.toString(), revoked.toString()), List.of(revoked.toString(), stale.toString()))) {
            var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(files);
            var verifier = new GitCommitSignatureService(properties);
            assertThat(verifier.verify(commit.build()).status()).isEqualTo(SignatureStatus.INVALID);
            assertThatThrownBy(() -> verifier.requireAcceptable(commit.build())).isInstanceOf(IOException.class);
        }
    }

    @Test void revokedPublicTrustMaterialDisablesMatchingLocalSecretSigningKey() throws Exception {
        var properties = properties(keys, true);
        var revoked = directory.resolve("revoked.asc");
        Files.write(revoked, armor(revokedRing(keys).getEncoded()));
        properties.setTrustedPublicKeyFiles(List.of(revoked.toString()));
        assertThatThrownBy(() -> new GitCommitSignatureService(properties)).isInstanceOf(IOException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updatedExpiryOverridesStalePublicCopyInEitherTrustFileOrder(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var updated = updatedCertificate(material, subkey, 60,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(70), material);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), updated)) {
            var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(files);
            var verifier = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.INVALID);
            assertThatThrownBy(() -> verifier.requireAcceptable(signed.build())).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updatedUsageOverridesStalePublicCopyInEitherTrustFileOrder(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var updated = updatedCertificate(material, subkey, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(70), material);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), updated)) {
            var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(files);
            var verifier = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.UNSUPPORTED);
            assertThatThrownBy(() -> verifier.requireAcceptable(signed.build())).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updatedExpiryDisablesMatchingLocalSecretSigningKeyInEitherOrder(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var updated = updatedCertificate(material, subkey, 60,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(70), material);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), updated)) {
            var properties = properties(material, true); properties.setTrustedPublicKeyFiles(files);
            assertThatThrownBy(() -> new GitCommitSignatureService(properties,
                    new MutableClock(created.plusSeconds(90)))).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updatedUsageDisablesMatchingLocalSecretSigningKeyInEitherOrder(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var updated = updatedCertificate(material, subkey, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(70), material);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), updated)) {
            var properties = properties(material, true); properties.setTrustedPublicKeyFiles(files);
            assertThatThrownBy(() -> new GitCommitSignatureService(properties,
                    new MutableClock(created.plusSeconds(90)))).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void signerUsesUpdatedExpiryAtRuntime(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var updated = updatedCertificate(material, subkey, 100,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(70), material);
        var updatedKeys = updated.getPublicKeys();
        var updatedKey = updatedKeys.next();
        if (subkey) updatedKey = updatedKeys.next();
        var afterUpdatedExpiry = updatedKey.getCreationTime().toInstant().plusSeconds(101);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), updated)) {
            var properties = properties(material, true); properties.setTrustedPublicKeyFiles(files);
            var clock = new MutableClock(created.plusSeconds(90));
            var signer = new GitCommitSignatureService(properties, clock);
            var beforeExpiry = commit(); signer.sign(beforeExpiry, "project", "main");
            assertThat(signer.verify(beforeExpiry.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
            clock.now = afterUpdatedExpiry;
            var afterExpiry = commit();
            assertThatThrownBy(() -> signer.sign(afterExpiry, "project", "main")).isInstanceOf(IOException.class);
            assertThat(afterExpiry.getGpgSignature()).isNull();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void newerAuthenticatedUpdateCanRestoreExpiryAndSigningPermission(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var restricted = updatedCertificate(material, subkey, 60,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(70), material);
        var renewed = updatedCertificate(material, subkey, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(80), material);
        for (var files : trustFileOrders(restricted, renewed)) {
            var properties = properties(material, true); properties.setTrustedPublicKeyFiles(files);
            var service = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
            var signed = commit(); service.sign(signed, "project", "main");
            assertThat(service.verify(signed.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unauthenticatedMetadataCannotOverrideTrustedCertificate(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var forged = updatedCertificate(material, subkey, 1,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(80), otherKeys);
        for (var files : trustFileOrders(material.generatePublicKeyRing(), forged)) {
            var properties = properties(material, true); properties.setTrustedPublicKeyFiles(files);
            var service = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
            assertThat(service.verify(signed.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
            var fresh = commit(); service.sign(fresh, "project", "main");
            assertThat(service.verify(fresh.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        }
    }

    @Test void unauthenticatedRevocationCannotDisableTrustedKey() throws Exception {
        var signed = commit(); configured(keys, true).sign(signed, "project", "main");
        var forged = revokedRing(keys, otherKeys);
        for (var files : trustFileOrders(keys.generatePublicKeyRing(), forged)) {
            var properties = properties(keys, true); properties.setTrustedPublicKeyFiles(files);
            var service = new GitCommitSignatureService(properties);
            assertThat(service.verify(signed.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unauthenticatedRenewalCannotRestoreExpiredSigningKey(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var expired = updatedCertificate(material, subkey, 60,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(70), material);
        var forgedRenewal = updatedCertificate(material, subkey, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(80), otherKeys);
        for (var files : trustFileOrders(expired, forgedRenewal)) {
            var verification = new GitSigningProperties(); verification.setTrustedPublicKeyFiles(files);
            var verifier = new GitCommitSignatureService(verification, new MutableClock(created.plusSeconds(90)));
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.INVALID);
            var signing = properties(material, true); signing.setTrustedPublicKeyFiles(files);
            assertThatThrownBy(() -> new GitCommitSignatureService(signing,
                    new MutableClock(created.plusSeconds(90)))).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void futureCertificateCannotRestoreExpiredKeyBeforeItsCreation(boolean subkey) throws Exception {
        var material = subkey ? signingSubkeyKeys() : keys;
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var expired = updatedCertificate(material, subkey, 60,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(70), material);
        var future = updatedCertificate(material, subkey, 240,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(100), material);
        for (var files : trustFileOrders(expired, future)) {
            var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(files);
            var clock = new MutableClock(created.plusSeconds(90));
            var verifier = new GitCommitSignatureService(properties, clock);
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.INVALID);
            var signing = properties(material, true); signing.setTrustedPublicKeyFiles(files);
            assertThatThrownBy(() -> new GitCommitSignatureService(signing, clock)).isInstanceOf(IOException.class);
            clock.now = created.plusSeconds(110);
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
            var signer = new GitCommitSignatureService(signing, clock);
            var fresh = commit(); signer.sign(fresh, "project", "main");
            assertThat(signer.verify(fresh.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        }
    }

    @Test void signerAppliesFutureUsageRestrictionOnlyWhenEffective() throws Exception {
        var created = keys.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var future = updatedCertificate(keys, false, 240,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(100), keys);
        for (var files : trustFileOrders(keys.generatePublicKeyRing(), future)) {
            var properties = properties(keys, true); properties.setTrustedPublicKeyFiles(files);
            var clock = new MutableClock(created.plusSeconds(90));
            var signer = new GitCommitSignatureService(properties, clock);
            var before = commit(); signer.sign(before, "project", "main");
            assertThat(signer.verify(before.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
            clock.now = created.plusSeconds(110);
            var after = commit();
            assertThatThrownBy(() -> signer.sign(after, "project", "main")).isInstanceOf(IOException.class);
            assertThat(after.getGpgSignature()).isNull();
            assertThat(signer.verify(before.build()).status()).isEqualTo(SignatureStatus.UNSUPPORTED);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void subkeyRequiresAnAuthenticatedPrimaryBinding(boolean forgedBinding) throws Exception {
        var material = signingSubkeyKeys();
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var bindingTime = created.plusSeconds(70);
        var ring = forgedBinding ? updatedCertificate(material, true, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, bindingTime, otherKeys) : material.generatePublicKeyRing();
        ring = retainOnlySubkeyBinding(ring, forgedBinding ? bindingTime : null);
        assertSubkeyCertificateRejected(material, ring, created.plusSeconds(90));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void signingSubkeyRequiresItsOwnAuthenticatedBacksignature(boolean forgedBacksignature) throws Exception {
        var material = signingSubkeyKeys();
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var bindingTime = created.plusSeconds(70);
        var ring = updatedCertificate(material, true, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, bindingTime, material,
                0, forgedBacksignature ? otherKeys : null);
        assertSubkeyCertificateRejected(material, ring, created.plusSeconds(90));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredBindingFallsBackOnlyToAnOlderLiveBinding(boolean retainOlderBinding) throws Exception {
        var material = signingSubkeyKeys();
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var created = material.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var bindingTime = created.plusSeconds(70);
        var ring = updatedCertificate(material, true, 120,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, bindingTime, material, 10, material);
        if (!retainOlderBinding) ring = retainOnlySubkeyBinding(ring, bindingTime);
        var file = directory.resolve("expired-binding.asc"); Files.write(file, armor(ring.getEncoded()));
        var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(List.of(file.toString()));
        var verifier = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
        assertThat(verifier.verify(signed.build()).status()).isEqualTo(
                retainOlderBinding ? SignatureStatus.VERIFIED : SignatureStatus.INVALID);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void equalTimeConflictingUpdatesUseRestrictivePolicyInEitherOrder(boolean expireKey) throws Exception {
        var signed = commit(); configured(keys, true).sign(signed, "project", "main");
        var created = keys.generatePublicKeyRing().getPublicKey().getCreationTime().toInstant();
        var restrictive = updatedCertificate(keys, false, expireKey ? 60 : 240,
                org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER, created.plusSeconds(70), keys);
        var permissive = updatedCertificate(keys, false, 240,
                org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA, created.plusSeconds(70), keys);
        for (var files : trustFileOrders(restrictive, permissive)) {
            var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(files);
            var verifier = new GitCommitSignatureService(properties, new MutableClock(created.plusSeconds(90)));
            assertThat(verifier.verify(signed.build()).status()).isEqualTo(
                    expireKey ? SignatureStatus.INVALID : SignatureStatus.UNSUPPORTED);
        }
    }

    private void assertSubkeyCertificateRejected(PGPKeyRingGenerator material, PGPPublicKeyRing invalid, Instant now)
            throws Exception {
        var signed = commit(); configured(material, true).sign(signed, "project", "main");
        var file = directory.resolve("invalid-subkey.asc"); Files.write(file, armor(invalid.getEncoded()));
        var properties = new GitSigningProperties(); properties.setTrustedPublicKeyFiles(List.of(file.toString()));
        var clock = new MutableClock(now);
        var verifier = new GitCommitSignatureService(properties, clock);
        assertThat(verifier.verify(signed.build()).status()).isEqualTo(SignatureStatus.INVALID);
        assertThatThrownBy(() -> verifier.requireAcceptable(signed.build())).isInstanceOf(IOException.class);
        var signing = properties(material, true);
        Files.write(Path.of(signing.getSecretKeyFile()), armor(
                PGPSecretKeyRing.replacePublicKeys(material.generateSecretKeyRing(), invalid).getEncoded()));
        assertThatThrownBy(() -> new GitCommitSignatureService(signing, clock)).isInstanceOf(IOException.class);
    }

    private static PGPPublicKeyRing retainOnlySubkeyBinding(PGPPublicKeyRing ring, Instant keep) {
        var keys = ring.getPublicKeys(); keys.next();
        var subkey = keys.next();
        for (var signatures = subkey.getSignatures(); signatures.hasNext();) {
            var signature = signatures.next();
            if (signature.getSignatureType() == PGPSignature.SUBKEY_BINDING
                    && (keep == null || !signature.getCreationTime().toInstant().equals(keep))) {
                subkey = PGPPublicKey.removeCertification(subkey, signature);
            }
        }
        return PGPPublicKeyRing.insertPublicKey(ring, subkey);
    }

    private List<List<String>> trustFileOrders(PGPPublicKeyRing stale, PGPPublicKeyRing updated) throws Exception {
        var oldFile = directory.resolve("stale-copy.asc");
        var newFile = directory.resolve("updated-copy.asc");
        Files.write(oldFile, armor(stale.getEncoded()));
        Files.write(newFile, armor(updated.getEncoded()));
        return List.of(List.of(oldFile.toString(), newFile.toString()), List.of(newFile.toString(), oldFile.toString()));
    }

    private static PGPPublicKeyRing updatedCertificate(PGPKeyRingGenerator material, boolean subkey,
            long validSeconds, int flags, Instant updatedAt, PGPKeyRingGenerator certifier) throws Exception {
        return updatedCertificate(material, subkey, validSeconds, flags, updatedAt, certifier, 0, material);
    }

    private static PGPPublicKeyRing updatedCertificate(PGPKeyRingGenerator material, boolean subkey,
            long validSeconds, int flags, Instant updatedAt, PGPKeyRingGenerator certifier,
            long signatureLifetime, PGPKeyRingGenerator subkeyCertifier) throws Exception {
        var ring = material.generatePublicKeyRing();
        var primary = ring.getPublicKey();
        var iterator = ring.getPublicKeys();
        var key = iterator.next();
        if (subkey) key = iterator.next();
        var privateKey = certifier.generateSecretKeyRing().getSecretKey().extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().setProvider(new BouncyCastleProvider()).build("test-only".toCharArray()));
        var generator = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(primary.getAlgorithm(),
                HashAlgorithmTags.SHA256).setProvider(new BouncyCastleProvider()), primary);
        generator.init(subkey ? PGPSignature.SUBKEY_BINDING : PGPSignature.POSITIVE_CERTIFICATION, privateKey);
        var hashed = new PGPSignatureSubpacketGenerator();
        hashed.setIssuerFingerprint(false, primary);
        hashed.setSignatureCreationTime(false, java.util.Date.from(updatedAt));
        hashed.setKeyExpirationTime(false, validSeconds);
        hashed.setKeyFlags(false, flags);
        if (signatureLifetime > 0) hashed.setSignatureExpirationTime(false, signatureLifetime);
        if (subkey && subkeyCertifier != null) {
            var backSecret = subkeyCertifier == material ? material.generateSecretKeyRing().getSecretKey(key.getKeyID())
                    : subkeyCertifier.generateSecretKeyRing().getSecretKey();
            var backPrivate = backSecret.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                    .setProvider(new BouncyCastleProvider()).build("test-only".toCharArray()));
            var backSignature = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(key.getAlgorithm(),
                    HashAlgorithmTags.SHA256).setProvider(new BouncyCastleProvider()), key);
            backSignature.init(PGPSignature.PRIMARYKEY_BINDING, backPrivate);
            var backPackets = new PGPSignatureSubpacketGenerator();
            backPackets.setSignatureCreationTime(false, java.util.Date.from(updatedAt));
            backSignature.setHashedSubpackets(backPackets.generate());
            hashed.addEmbeddedSignature(false, backSignature.generateCertification(primary, key));
        }
        generator.setHashedSubpackets(hashed.generate());
        var unhashed = new PGPSignatureSubpacketGenerator();
        unhashed.setIssuerKeyID(false, primary.getKeyID());
        generator.setUnhashedSubpackets(unhashed.generate());
        PGPPublicKey updated;
        if (subkey) {
            var signature = roundTripSignature(generator.generateCertification(primary, key));
            assertThat(signature.getKeyID()).isEqualTo(primary.getKeyID());
            updated = PGPPublicKey.addCertification(key, signature);
        } else {
            var userId = primary.getUserIDs().next();
            var signature = roundTripSignature(generator.generateCertification(userId, primary));
            assertThat(signature.getKeyID()).isEqualTo(primary.getKeyID());
            updated = PGPPublicKey.addCertification(primary, userId, signature);
        }
        return PGPPublicKeyRing.insertPublicKey(ring, updated);
    }

    private static PGPSignature roundTripSignature(PGPSignature signature) throws Exception {
        // A generated signature caches the real private-key ID. Imported certificates
        // instead obtain the claimed issuer ID from their serialized subpackets.
        var objects = new PGPObjectFactory(new java.io.ByteArrayInputStream(signature.getEncoded()),
                new JcaKeyFingerprintCalculator());
        return ((PGPSignatureList) objects.nextObject()).get(0);
    }

    @Test void primaryRevocationDominatesStaleSigningSubkeyTrust() throws Exception {
        var subkeyKeys = signingSubkeyKeys();
        var signer = configured(subkeyKeys, true);
        var commit = commit(); signer.sign(commit, "project", "main");
        var stale = directory.resolve("stale-subkey.asc");
        var revoked = directory.resolve("revoked-subkey.asc");
        Files.write(stale, armor(subkeyKeys.generatePublicKeyRing().getEncoded()));
        Files.write(revoked, armor(revokedRing(subkeyKeys).getEncoded()));
        var properties = new GitSigningProperties();
        properties.setTrustedPublicKeyFiles(List.of(stale.toString(), revoked.toString()));
        assertThat(new GitCommitSignatureService(properties).verify(commit.build()).status()).isEqualTo(SignatureStatus.INVALID);
    }

    @Test void signingSubkeyCannotKeepSigningAfterPrimaryExpiresAtRuntime() throws Exception {
        var subkeyKeys = signingSubkeyKeys();
        var primary = subkeyKeys.generatePublicKeyRing().getPublicKey();
        var clock = new MutableClock(primary.getCreationTime().toInstant().plusSeconds(90));
        var signer = new GitCommitSignatureService(properties(subkeyKeys, true), clock);
        var beforeExpiry = commit(); signer.sign(beforeExpiry, "project", "main");
        assertThat(signer.verify(beforeExpiry.build()).status()).isEqualTo(SignatureStatus.VERIFIED);
        clock.now = primary.getCreationTime().toInstant().plusSeconds(121);
        var afterExpiry = commit();
        assertThatThrownBy(() -> signer.sign(afterExpiry, "project", "main")).isInstanceOf(IOException.class);
        assertThat(afterExpiry.getGpgSignature()).isNull();
    }

    private static PGPKeyRingGenerator signingSubkeyKeys() throws Exception {
        var packets = new PGPSignatureSubpacketGenerator();
        packets.setKeyFlags(false, org.bouncycastle.bcpg.sig.KeyFlags.CERTIFY_OTHER);
        packets.setKeyExpirationTime(false, 120);
        var generator = generateKeys("Subkey operator <subkey@example.test>", packets.generate());
        var rsa = java.security.KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        var signingFlags = new PGPSignatureSubpacketGenerator();
        signingFlags.setKeyFlags(false, org.bouncycastle.bcpg.sig.KeyFlags.SIGN_DATA);
        generator.addSubKey(new JcaPGPKeyPair(org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_SIGN,
                        rsa.generateKeyPair(), java.util.Date.from(Instant.now().minusSeconds(60))),
                signingFlags.generate(), null, new JcaPGPContentSignerBuilder(
                        org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_SIGN, HashAlgorithmTags.SHA256)
                        .setProvider(new BouncyCastleProvider()));
        return generator;
    }

    private static PGPPublicKeyRing revokedRing(PGPKeyRingGenerator generator) throws Exception {
        return revokedRing(generator, generator);
    }

    private static PGPPublicKeyRing revokedRing(PGPKeyRingGenerator generator, PGPKeyRingGenerator certifier) throws Exception {
        var ring = generator.generatePublicKeyRing();
        var primary = ring.getPublicKey();
        var privateKey = certifier.generateSecretKeyRing().getSecretKey().extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().setProvider(new BouncyCastleProvider()).build("test-only".toCharArray()));
        var revocation = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(primary.getAlgorithm(),
                HashAlgorithmTags.SHA256).setProvider(new BouncyCastleProvider()), primary);
        revocation.init(PGPSignature.KEY_REVOCATION, privateKey);
        var unhashed = new PGPSignatureSubpacketGenerator();
        unhashed.setIssuerKeyID(false, primary.getKeyID());
        revocation.setUnhashedSubpackets(unhashed.generate());
        var signature = roundTripSignature(revocation.generateCertification(primary));
        assertThat(signature.getKeyID()).isEqualTo(primary.getKeyID());
        return PGPPublicKeyRing.insertPublicKey(ring, PGPPublicKey.addCertification(primary, signature));
    }

    private static final class MutableClock extends java.time.Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void nativeGitVerifiesGeneratedCommitWhenGitAndGpgAvailable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(commandAvailable("git") && commandAvailable("gpg"));
        var service = configured(keys, true);
        var home = Files.createDirectory(directory.resolve("gnupg"));
        try { Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
        var publicFile = directory.resolve("public.asc");
        Files.write(publicFile, armor(keys.generatePublicKeyRing().getEncoded()));
        assertThat(run(List.of("gpg", "--homedir", home.toString(), "--batch", "--import", publicFile.toString()), null).exit()).isZero();
        var repository = directory.resolve("git");
        assertThat(run(List.of("git", "init", "--bare", repository.toString()), null).exit()).isZero();
        var commit = commit(); service.sign(commit, "project", "main");
        var hash = run(List.of("git", "-C", repository.toString(), "hash-object", "-w", "-t", "commit", "--stdin"), commit.build());
        assertThat(hash.exit()).isZero();
        var verify = run(List.of("git", "-C", repository.toString(), "-c", "gpg.program=gpg", "verify-commit", hash.output().trim()), null, home);
        assertThat(verify.exit()).withFailMessage(verify.output()).isZero();
    }

    private static boolean commandAvailable(String command) throws Exception {
        try { return run(List.of(command, "--version"), null).exit() == 0; }
        catch (IOException e) { return false; }
    }
    private record ProcessResult(int exit, String output) { }
    private static ProcessResult run(List<String> command, byte[] input) throws Exception { return run(command, input, null); }
    private static ProcessResult run(List<String> command, byte[] input, Path gnupgHome) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (gnupgHome != null) builder.environment().put("GNUPGHOME", gnupgHome.toString());
        var process = builder.start();
        try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input); }
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Native Git/GPG test process timed out");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }
    private static void signWithSubpackets(CommitBuilder commit, PGPSignatureSubpacketVector packets) throws Exception {
        var secret = keys.generateSecretKeyRing().getSecretKey();
        var privateKey = secret.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder()
                .setProvider(new BouncyCastleProvider()).build("test-only".toCharArray()));
        var generator = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(secret.getPublicKey().getAlgorithm(),
                HashAlgorithmTags.SHA256).setProvider(new BouncyCastleProvider()), secret.getPublicKey());
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey); generator.setHashedSubpackets(packets);
        generator.update(commit.build());
        commit.setGpgSignature(new org.eclipse.jgit.lib.GpgSignature(armor(generator.generate().getEncoded())));
    }

    private GitCommitSignatureService configured(PGPKeyRingGenerator generator, boolean enabled) throws Exception {
        return new GitCommitSignatureService(properties(generator, enabled));
    }
    private GitSigningProperties properties(PGPKeyRingGenerator generator, boolean enabled) throws Exception {
        return SigningTestKeys.properties(directory, generator, enabled);
    }
    private static CommitBuilder commit() {
        var builder = new CommitBuilder();
        builder.setTreeId(ObjectId.fromString("4b825dc642cb6eb9a060e54bf8d69288fbee4904"));
        var identity = new PersonIdent("alice", "alice@example.test");
        builder.setAuthor(identity); builder.setCommitter(identity); builder.setMessage("model change\n");
        return builder;
    }
}
