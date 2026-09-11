package com.taxonomy.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StableIdentityHashTest {

    @Test
    void sha256PreservesCompatibilitySensitivePersistedDigests() {
        assertEquals("e292cc8d7644151fe4311a598f1a90c5fb243486f79ab6e9dbc9969e775b0177",
                StableIdentityHash.sha256("repo-a\u0000workspace-a\u0000feature/a"));
        assertEquals("e2787b6c13a87f9037ffb0d11dc6affca929db85a6c04e00048507061e09fce6",
                StableIdentityHash.sha256(
                        "00000000-0000-0000-0000-000000000001\u0000external-id"));
        assertEquals("a825f2417f7cc5f6a54338f3d3eafd3738a7baab8543dfe99939d84c1ee075c2",
                StableIdentityHash.sha256("version:example"));
    }
}
