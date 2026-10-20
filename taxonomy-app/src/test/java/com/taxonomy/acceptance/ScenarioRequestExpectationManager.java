package com.taxonomy.acceptance;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.SimpleRequestExpectationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;

/** Scenario-only transport history; expectation matching remains Spring's implementation. */
class ScenarioRequestExpectationManager extends SimpleRequestExpectationManager {
    @Override
    public ClientHttpResponse validateRequest(ClientHttpRequest request) throws IOException {
        if (!(request instanceof MockClientHttpRequest mock)) return super.validateRequest(request);
        var recorded = new RecordedRequest(mock);
        // Keep Spring's ordering, counters and failure bookkeeping intact. In particular,
        // a failed matcher or response creator retains its original request for diagnostics.
        ClientHttpResponse response = super.validateRequest(recorded);
        recorded.complete();
        return response;
    }

    /** Spring owns this stable history identity; its successful transport payload is not history. */
    private static final class RecordedRequest extends MockClientHttpRequest {
        private volatile MockClientHttpRequest active;
        private boolean executed;

        RecordedRequest(MockClientHttpRequest original) {
            super(original.getMethod(), original.getURI());
            active = original;
        }

        void complete() {
            MockClientHttpRequest original = active;
            super.setMethod(original.getMethod());
            super.setURI(original.getURI());
            original.getHeaders().forEach((name, values) -> super.getHeaders().put(name, new ArrayList<>(values)));
            super.getAttributes().putAll(original.getAttributes());
            executed = original.isExecuted();
            // Publish the complete metadata before releasing the only transport reference.
            // No list mutation, expectation reset or lock around a response creator is needed.
            active = null;
        }

        @Override public HttpMethod getMethod() {
            var original = active; return original == null ? super.getMethod() : original.getMethod();
        }
        @Override public void setMethod(HttpMethod method) {
            var original = active; if (original == null) super.setMethod(method); else original.setMethod(method);
        }
        @Override public URI getURI() {
            var original = active; return original == null ? super.getURI() : original.getURI();
        }
        @Override public void setURI(URI uri) {
            var original = active; if (original == null) super.setURI(uri); else original.setURI(uri);
        }
        @Override public HttpHeaders getHeaders() {
            var original = active; return original == null ? super.getHeaders() : original.getHeaders();
        }
        @Override public Map<String, Object> getAttributes() {
            var original = active; return original == null ? super.getAttributes() : original.getAttributes();
        }
        @Override public OutputStream getBody() throws IOException {
            var original = active; return original == null ? super.getBody() : original.getBody();
        }
        @Override public byte[] getBodyAsBytes() {
            var original = active; return original == null ? super.getBodyAsBytes() : original.getBodyAsBytes();
        }
        @Override public String getBodyAsString() {
            var original = active; return original == null ? super.getBodyAsString() : original.getBodyAsString();
        }
        @Override public String getBodyAsString(Charset charset) {
            var original = active; return original == null ? super.getBodyAsString(charset) : original.getBodyAsString(charset);
        }
        @Override public boolean isExecuted() {
            var original = active; return original == null ? executed || super.isExecuted() : original.isExecuted();
        }
        @Override public void setResponse(ClientHttpResponse response) {
            var original = active; if (original == null) super.setResponse(response); else original.setResponse(response);
        }
        @Override protected ClientHttpResponse executeInternal() throws IOException {
            var original = active; return original == null ? super.executeInternal() : original.execute();
        }
        @Override public String toString() {
            var original = active; return original == null ? super.toString() : original.toString();
        }
    }
}
