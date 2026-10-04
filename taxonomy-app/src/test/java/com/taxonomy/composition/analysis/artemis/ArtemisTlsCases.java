package com.taxonomy.composition.analysis.artemis;

import java.util.List;

/** Shared assertions for JUnit and the dependency-free local verification entry point. */
final class ArtemisTlsCases {
    record Case(String name, String url, boolean tlsRequired, boolean accepted) { }

    static List<Case> cases() {
        return List.of(
            new Case("single TLS endpoint", "tcp://broker:61617?sslEnabled=true", true, true),
            new Case("every failover endpoint uses TLS", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)", true, true),
            new Case("TLS failover with HA options", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)?ha=true", true, true),
            new Case("safe global option does not weaken secured connectors", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)?sslEnabled=true", true, true),
            new Case("encoded global override is rejected", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)?ssl%45nabled=false", true, false),
            new Case("encoded duplicate option is rejected", "tcp://a:61617?sslEnabled=true&ssl%45nabled=false", true, false),
            new Case("unparenthesized list is not valid failover", "tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true", true, false),
            new Case("IPv6 TLS endpoint", "tcp://[::1]:61617?sslEnabled=true", true, true),
            new Case("credentials do not become log output", "tcp://analysis:secret-marker@broker:61617?sslEnabled=true", true, true),
            new Case("unrelated value cannot disable TLS", "tcp://broker:61617?sslEnabled=true&trustStorePassword=sslEnabled=false", true, true),
            new Case("explicit isolated plaintext mode", "tcp://broker:61616", false, true),
            new Case("isolated plaintext failover", "(tcp://a:61616,tcp://b:61616)?ha=true", false, true),
            new Case("in-process broker remains usable", "vm://0", true, true),
            new Case("first connector is plaintext", "(tcp://plain:61616,tcp://tls:61617?sslEnabled=true)", true, false),
            new Case("last connector is plaintext", "(tcp://tls:61617?sslEnabled=true,tcp://plain:61616)", true, false),
            new Case("middle connector is plaintext", "(tcp://a:61617?sslEnabled=true,tcp://plain:61616,tcp://b:61617?sslEnabled=true)", true, false),
            new Case("global option does not authorize plaintext connectors", "(tcp://a:61616,tcp://b:61616)?sslEnabled=true", true, false),
            new Case("global option must not override connector TLS", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true)?sslEnabled=false", true, false),
            new Case("misleading option name", "tcp://broker:61617?notsslEnabled=true", true, false),
            new Case("wrong option case", "tcp://broker:61617?sslenabled=true", true, false),
            new Case("value is not exactly true", "tcp://broker:61617?sslEnabled=trueish", true, false),
            new Case("nested option in another value", "tcp://broker:61617?trustStorePassword=sslEnabled=true", true, false),
            new Case("fragment is not a TLS option", "tcp://broker:61617#sslEnabled=true", true, false),
            new Case("fragment cannot add plaintext connectors", "tcp://broker:61617?sslEnabled=true#tcp://plain:61616", true, false),
            new Case("encoded alternate connector is rejected", "tcp://broker:61617?sslEnabled=true#tcp%3A%2F%2Fplain%3A61616", true, false),
            new Case("explicit false", "tcp://broker:61617?sslEnabled=false", true, false),
            new Case("contradictory repeated options", "tcp://broker:61617?sslEnabled=true&sslEnabled=false", true, false),
            new Case("ambiguous repeated option", "tcp://broker:61617?sslEnabled=true&sslEnabled=true", true, false),
            new Case("no explicit TLS", "tcp://broker:61616", true, false),
            new Case("unknown second connector scheme", "(tcp://a:61617?sslEnabled=true,http://b:61617?sslEnabled=true)", true, false),
            new Case("missing closing parenthesis", "(tcp://a:61617?sslEnabled=true,tcp://b:61617?sslEnabled=true", true, false),
            new Case("empty connector", "(tcp://a:61617?sslEnabled=true,)", true, false),
            new Case("unexpected composite suffix", "(tcp://a:61617?sslEnabled=true)extra", true, false),
            new Case("invalid URI remains credential-free", "tcp://analysis:secret-marker@broker:bad?sslEnabled=true", true, false)
        );
    }

    static void verify(Case test) {
        ArtemisAnalysisSettings settings = null;
        try {
            settings = new ArtemisAnalysisSettings(test.url(), test.tlsRequired(), 1000, 30000);
        } catch (IllegalStateException failure) {
            if (test.accepted()) throw new AssertionError(test.name() + ": valid configuration rejected", failure);
            if (failure.getMessage().contains("secret-marker") || failure.getCause() != null)
                throw new AssertionError("Configuration failure must not expose the broker URL", failure);
            return;
        }
        if (!test.accepted()) throw new AssertionError(test.name() + ": unsafe configuration accepted");
        if (settings.toString().contains("secret-marker") || settings.toString().contains("broker:"))
            throw new AssertionError("Settings must not render broker URL or credentials");
    }

    public static void main(String[] args) {
        int failures = 0;
        for (Case test : cases()) {
            try { verify(test); System.out.println("PASS " + test.name()); }
            catch (AssertionError error) { failures++; System.out.println("FAIL " + error.getMessage()); }
        }
        System.out.println("TLS cases=" + cases().size() + ", failed=" + failures);
        if (failures != 0) throw new AssertionError(failures + " TLS regressions failed");
    }
}
