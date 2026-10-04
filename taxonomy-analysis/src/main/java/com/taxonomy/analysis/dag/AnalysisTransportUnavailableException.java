package com.taxonomy.analysis.dag;

/**
 * A transport could not accept a message because its broker is not reachable.
 *
 * <p>The message was <em>not</em> accepted. Callers keep the durable dispatch
 * intent and retry only through a bounded recovery trigger (startup, explicit
 * repair or broker reconnection), never through a fixed-rate scan. The message
 * text never contains broker URLs, credentials or payload content.</p>
 */
public final class AnalysisTransportUnavailableException extends RuntimeException {

    public AnalysisTransportUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
