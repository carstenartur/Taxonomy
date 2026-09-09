package com.taxonomy.interop.oslc;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OslcRemoteProfilesTest {
    @Test
    void nullBindingValueMeansNoConfiguredRemotes() {
        var profiles = new OslcRemoteProfiles();
        profiles.setRemotes(null);
        assertEquals(Map.of(), profiles.getRemotes());
    }

    @Test
    void configuredRemotesAreCopiedAndImmutable() {
        var profiles = new OslcRemoteProfiles();
        var profile = new OslcRemoteProfiles.RemoteProfile("repo", "USER:alice", URI.create("https://example.test/oslc/"),
                "TOKEN_ENV", false, false);
        profiles.setRemotes(Map.of("reference", profile));
        assertEquals(profile, profiles.getRemotes().get("reference"));
        assertThrows(UnsupportedOperationException.class,
                () -> profiles.getRemotes().put("other", profile));
    }
}
