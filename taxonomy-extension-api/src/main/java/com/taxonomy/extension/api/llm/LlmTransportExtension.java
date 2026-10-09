package com.taxonomy.extension.api.llm;

/** An executable, startup-bound provider contribution, independent of host frameworks. */
public interface LlmTransportExtension extends LlmProviderExtension {
    LlmTransport transport();
}
