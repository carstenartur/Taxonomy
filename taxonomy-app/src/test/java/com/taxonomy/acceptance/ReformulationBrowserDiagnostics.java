package com.taxonomy.acceptance;

import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Failure-only transport metadata; never retains raw protocol messages, headers, or bodies. */
final class ReformulationBrowserDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EVENTS = Set.of("Network.requestWillBeSent", "Network.responseReceived",
            "Network.dataReceived", "Network.loadingFinished", "Network.loadingFailed");

    private ReformulationBrowserDiagnostics() { }

    static void capture(RemoteWebDriver driver, Path output) {
        // This application JVM snapshot must survive even if a subsequent driver request blocks.
        try {
            var threads = Thread.getAllStackTraces().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .map(entry -> Map.of("name", entry.getKey().getName(), "id", entry.getKey().threadId(),
                            "state", entry.getKey().getState().name(),
                            "stack", Arrays.stream(entry.getValue()).map(StackTraceElement::toString).toList())).toList();
            Files.writeString(output.resolve("failure-threads.json"), JSON.writeValueAsString(threads));
        } catch (Exception failure) { unavailable(output, "threads", failure); }
        try {
            var events = new ArrayList<Map<String, Object>>();
            for (var entry : driver.manage().logs().get(LogType.PERFORMANCE)) {
                JsonNode message = JSON.readTree(entry.getMessage()).path("message");
                String eventName = message.path("method").asText();
                if (!EVENTS.contains(eventName)) continue;
                JsonNode parameters = message.path("params");
                var event = fields(parameters, "requestId", "loaderId", "timestamp", "dataLength", "encodedDataLength",
                        "errorText", "canceled", "blockedReason");
                event.put("event", eventName);
                if (parameters.has("request")) event.put("request", fields(parameters.path("request"), "url", "method"));
                if (parameters.has("response")) {
                    JsonNode source = parameters.path("response");
                    var response = fields(source, "url", "status", "protocol", "connectionId", "connectionReused", "encodedDataLength");
                    var framing = new LinkedHashMap<String, Object>();
                    for (var header : source.path("headers").properties()) {
                        String name = header.getKey().toLowerCase(Locale.ROOT);
                        if (name.equals("content-length") || name.equals("transfer-encoding")) framing.put(name, header.getValue());
                    }
                    response.put("framing", framing); event.put("response", response);
                }
                events.add(event);
            }
            Files.writeString(output.resolve("failure-network.json"), JSON.writeValueAsString(events));
        } catch (Exception failure) { unavailable(output, "network", failure); }
    }

    static void unavailable(Path output, String operation, Exception failure) {
        try {
            // Exception messages may contain raw protocol data; retain only the failure category.
            Files.writeString(output.resolve("failure-" + operation + "-unavailable.json"),
                    JSON.writeValueAsString(Map.of("operation", operation, "exceptionType", failure.getClass().getName())));
        } catch (Exception ignored) { /* Diagnostics must never replace the scenario's original failure. */ }
    }

    private static Map<String, Object> fields(JsonNode source, String... names) {
        var result = new LinkedHashMap<String, Object>();
        for (String name : names) if (source.has(name)) result.put(name, source.path(name));
        return result;
    }
}
