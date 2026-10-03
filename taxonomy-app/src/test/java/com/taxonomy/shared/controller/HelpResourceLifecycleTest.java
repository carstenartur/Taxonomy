package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;

/** Executes the same real-controller assertions as the standalone diagnostic runner. */
class HelpResourceLifecycleTest {
    @Test void fallbackLocalesShareOneCachedResource() throws Exception {
        HelpResourceLifecycleAssertions.fallbackCache();
    }
    @Test void distinctTranslationsRemainIsolated() throws Exception {
        HelpResourceLifecycleAssertions.languageIsolation();
    }
    @Test void successfulImageReadClosesTheResource() throws Exception {
        HelpResourceLifecycleAssertions.imageSuccess();
    }
    @Test void failedImageReadClosesTheResource() throws Exception {
        HelpResourceLifecycleAssertions.imageFailure();
    }
    @Test void existingAllowlistAndErrorBoundariesRemainIntact() {
        HelpResourceLifecycleAssertions.boundaries();
    }
    @Test void unavailableResourcesDoNotPoisonTheCache() {
        HelpResourceLifecycleAssertions.missingResourceIsNotCached();
    }
}
