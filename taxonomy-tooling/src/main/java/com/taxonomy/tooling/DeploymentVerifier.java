package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-bound Render rollout verification and its delivery evidence helpers. */
final class DeploymentVerifier {
  static final Set<String> RENDER_FAILURE_STATES =
      Set.of(
          "build_failed",
          "update_failed",
          "pre_deploy_failed",
          "canceled",
          "cancelled",
          "deactivated",
          "failed");
  private static final String USER_AGENT = "taxonomy-delivery-check";

  interface Transport {
    Object json(URI uri, Map<String, String> headers) throws IOException, InterruptedException;

    int status(URI uri) throws IOException, InterruptedException;
  }

  @FunctionalInterface
  interface Pause {
    void sleep(long millis) throws InterruptedException;
  }

  record Runtime(Transport transport, Clock clock, Pause pause) {}

  record Result(boolean ok, String detail) {}

  record Decision(String state, String detail) {}

  /** Rechecks an archived producer receipt without contacting or publishing to its target. */
  static void verifyReceipt(Map<String, Object> receipt, String source, boolean publication) {
    if (!(receipt.get("schemaVersion") instanceof Number)
        || QualitySite.number(receipt, "schemaVersion") != 2
        || !source.equals(receipt.get("expectedCommit")))
      throw new IllegalArgumentException("Deployment receipt schema/source differs");
    URI target = URI.create(receiptText(receipt, "targetUrl"));
    if (!("https".equalsIgnoreCase(target.getScheme())
            || "http".equalsIgnoreCase(target.getScheme()))
        || target.getHost() == null)
      throw new IllegalArgumentException("Deployment receipt target must be an HTTP(S) URL");
    Instant started = Instant.parse(receiptText(receipt, "startedAt"));
    Instant ended = Instant.parse(receiptText(receipt, "endedAt"));
    if (ended.isBefore(started))
      throw new IllegalArgumentException("Deployment receipt timestamps are reversed");
    receiptText(receipt, "detail");
    String state = receiptText(receipt, "deploymentState");
    String result = receiptText(receipt, "result");
    // Disabled is the existing explicit opt-out, never a successful deployment gate.
    if (state.equals("disabled") && result.equals("disabled")) return;
    if (!state.equals("succeeded") || !result.equals("success"))
      throw new IllegalArgumentException(
          "Deployment receipt is failed, incomplete or inconsistent");
    if (!publication)
      throw new IllegalArgumentException("A non-publishing manifest cannot claim live deployment");
    if (!(receipt.get("attempts") instanceof Number)
        || QualitySite.number(receipt, "attempts") <= 0
        || !(receipt.get("renderApiStatusVerification") instanceof Boolean))
      throw new IllegalArgumentException("Deployment receipt verification details are invalid");
    if (Boolean.TRUE.equals(receipt.get("renderApiStatusVerification"))) {
      receiptText(receipt, "renderDeployId");
      if (!"live".equals(receipt.get("renderDeployStatus")))
        throw new IllegalArgumentException(
            "Deployment receipt did not verify a live Render deploy");
    }
  }

  private static String receiptText(Map<String, Object> receipt, String key) {
    if (!(receipt.get(key) instanceof String value) || value.isBlank())
      throw new IllegalArgumentException("Invalid deployment receipt field: " + key);
    return value;
  }

  static int run(
      String[] raw, Map<String, String> environment, Path cwd, PrintStream out, PrintStream err) {
    return run(
        raw,
        environment,
        cwd,
        out,
        err,
        new Runtime(httpTransport(), Clock.systemUTC(), Thread::sleep));
  }

  static int run(
      String[] raw,
      Map<String, String> environment,
      Path cwd,
      PrintStream out,
      PrintStream err,
      Runtime runtime) {
    try {
      var args = TaxonomyTooling.Arguments.parse(raw);
      String base = stripTrailingSlashes(args.required("base-url"));
      String expected = args.required("expected-commit");
      int attempts = Math.max(1, Integer.parseInt(args.optionalOrDefault("attempts", "60")));
      double seconds = Double.parseDouble(args.optionalOrDefault("interval-seconds", "10"));
      if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Invalid polling interval");
      long interval = (long) (Math.max(0, seconds) * 1000);
      Path evidenceFile = resolve(cwd, args.optional("evidence-file"));
      String renderDeployId = loadDeployId(resolve(cwd, args.optional("deploy-response-file")));
      String service = args.optional("render-service-id");
      String key =
          args.optionalOrDefault("render-api-key", environment.getOrDefault("RENDER_API_KEY", ""));
      boolean apiEnabled =
          key != null
              && !key.isEmpty()
              && service != null
              && !service.isEmpty()
              && renderDeployId != null;
      var evidence =
          QualitySite.map(
              "schemaVersion",
              2,
              "deploymentState",
              "verifying",
              "targetUrl",
              base,
              "expectedCommit",
              expected,
              "startedAt",
              now(runtime.clock()),
              "renderDeployId",
              renderDeployId,
              "renderApiStatusVerification",
              apiEnabled,
              "result",
              "in_progress");
      writeEvidence(evidenceFile, evidence);
      String lastError = "deployment did not answer", renderDeployStatus = null;
      int lastAttempt = 0;
      for (int attempt = 1; attempt <= attempts; attempt++) {
        lastAttempt = attempt;
        try {
          boolean verifyApplication = true;
          if (apiEnabled) {
            renderDeployStatus =
                deployStatus(fetchRenderDeploy(runtime.transport(), service, renderDeployId, key));
            evidence.put("renderDeployStatus", renderDeployStatus);
            Decision decision = renderStatusDecision(renderDeployId, renderDeployStatus);
            if (decision.state().equals("failure")) {
              finishEvidence(
                  evidence,
                  runtime.clock(),
                  attempt,
                  "failed",
                  "failure",
                  decision.detail(),
                  renderDeployStatus);
              writeEvidence(evidenceFile, evidence);
              err.println("::error::" + decision.detail());
              return 1;
            }
            verifyApplication = decision.state().equals("live");
            if (!verifyApplication) lastError = decision.detail();
          }
          if (verifyApplication) {
            Result result = verifyOnce(base, expected, runtime.transport());
            if (result.ok()) {
              finishEvidence(
                  evidence,
                  runtime.clock(),
                  attempt,
                  "succeeded",
                  "success",
                  result.detail(),
                  renderDeployStatus);
              writeEvidence(evidenceFile, evidence);
              out.println(
                  "Render deployment verified on attempt " + attempt + ": " + result.detail());
              return 0;
            }
            lastError = result.detail();
          }
        } catch (IOException | IllegalArgumentException exception) {
          lastError = safeError(exception);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          lastError = "Deployment verification interrupted";
          break;
        }
        out.println(
            "Render verification attempt " + attempt + "/" + attempts + " not ready: " + lastError);
        if (attempt < attempts) {
          try {
            runtime.pause().sleep(interval);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            lastError = "Deployment verification interrupted";
            break;
          }
        }
      }
      finishEvidence(
          evidence,
          runtime.clock(),
          lastAttempt,
          "failed",
          "failure",
          lastError,
          renderDeployStatus);
      writeEvidence(evidenceFile, evidence);
      err.println("::error::Render deployment verification failed: " + lastError);
      return 1;
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      err.println("::error::" + safeError(exception));
      return 1;
    }
  }

  static int renderCommand(
      String command,
      String[] raw,
      Map<String, String> environment,
      Path cwd,
      PrintStream out,
      PrintStream err) {
    try {
      if (command.equals("render-deploy-url")) {
        out.println(
            pinnedHookUrl(
                required(environment, "HOOK_URL"), required(environment, "EXPECTED_SHA")));
      } else if (command.equals("render-service-id")) {
        String configured = environment.getOrDefault("CONFIGURED_RENDER_SERVICE_ID", "");
        out.println(
            configured.isEmpty() ? serviceId(required(environment, "HOOK_URL")) : configured);
      } else if (command.equals("render-delivery-state")) {
        var args = TaxonomyTooling.Arguments.parse(raw);
        String state = args.required("state");
        String deploymentState, result, detail;
        switch (state) {
          case "disabled" -> {
            deploymentState = "disabled";
            result = "disabled";
            detail = "Render deployment is explicitly disabled by RENDER_DEPLOY_ENABLED.";
          }
          case "configuration-failure" -> {
            deploymentState = "failed";
            result = "failure";
            detail = "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing.";
          }
          case "hook-failure" -> {
            deploymentState = "failed";
            result = "failure";
            detail = "Render deploy hook request failed after bounded retries.";
          }
          case "triggered" -> {
            deploymentState = "triggered";
            result = "in_progress";
            detail = "Render deploy hook accepted the verified commit; live verification follows.";
          }
          default -> throw new IllegalArgumentException("Unsupported delivery evidence state");
        }
        String at = now(Clock.systemUTC());
        writeEvidence(
            cwd.resolve("target/delivery-evidence/render-" + state + ".json"),
            QualitySite.map(
                "schemaVersion",
                2,
                "deploymentState",
                deploymentState,
                "result",
                result,
                "targetUrl",
                stripTrailingSlashes(required(environment, "BASE_URL")),
                "expectedCommit",
                required(environment, "EXPECTED_SHA"),
                "startedAt",
                at,
                "endedAt",
                at,
                "detail",
                detail));
      } else throw new IllegalArgumentException("Unknown Render delivery command");
      return 0;
    } catch (Exception exception) {
      err.println("::error::" + safeError(exception));
      return 1;
    }
  }

  static boolean containsCommit(Object payload, String expected) {
    if (payload instanceof String value) {
      String candidate = value.strip().toLowerCase(Locale.ROOT),
          wanted = expected.strip().toLowerCase(Locale.ROOT);
      return candidate.equals(wanted)
          || candidate.length() >= 7
              && (candidate.startsWith(wanted) || wanted.startsWith(candidate));
    }
    if (payload instanceof Map<?, ?> map)
      return map.values().stream().anyMatch(value -> containsCommit(value, expected));
    if (payload instanceof Iterable<?> items)
      for (Object item : items) if (containsCommit(item, expected)) return true;
    return false;
  }

  static String deployId(Object payload) {
    if (payload instanceof Map<?, ?> map) {
      if (map.get("id") instanceof String id && id.startsWith("dep-")) return id;
      for (Object value : map.values()) {
        String found = deployId(value);
        if (found != null) return found;
      }
    } else if (payload instanceof Iterable<?> items) {
      for (Object item : items) {
        String found = deployId(item);
        if (found != null) return found;
      }
    }
    return null;
  }

  static String deployStatus(Object payload) {
    if (payload instanceof Map<?, ?> map) {
      if (map.get("status") instanceof String status)
        return status.strip().toLowerCase(Locale.ROOT);
      for (String key : new String[] {"deploy", "data"}) {
        String nested = deployStatus(map.get(key));
        if (nested != null && !nested.isEmpty()) return nested;
      }
    }
    return null;
  }

  static Decision renderStatusDecision(String deployId, String status) {
    String normalized = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
    if (RENDER_FAILURE_STATES.contains(normalized))
      return new Decision("failure", "Render deploy " + deployId + " ended with " + normalized);
    if (!normalized.equals("live"))
      return new Decision(
          "pending",
          "Render deploy "
              + deployId
              + " is "
              + (normalized.isEmpty() ? "unknown" : normalized)
              + "; waiting for live");
    return new Decision("live", "Render deploy " + deployId + " is live");
  }

  static Result verifyOnce(String serviceUrl, String expected, Transport transport)
      throws IOException, InterruptedException {
    String base = stripTrailingSlashes(serviceUrl);
    Object readiness = transport.json(URI.create(base + "/actuator/health/readiness"), Map.of());
    if (!(readiness instanceof Map<?, ?> map) || !"UP".equals(map.get("status")))
      return new Result(false, "readiness is not UP");
    Object info = transport.json(URI.create(base + "/actuator/info"), Map.of());
    if (!containsCommit(info, expected))
      return new Result(false, "actuator info does not identify expected commit " + expected);
    int status = transport.status(URI.create(base + "/"));
    if (status < 200 || status >= 400)
      return new Result(false, "root smoke test returned HTTP " + status);
    return new Result(true, "readiness is UP, expected commit is live, and root smoke test passed");
  }

  static Object fetchRenderDeploy(Transport transport, String service, String deploy, String key)
      throws IOException, InterruptedException {
    return transport.json(
        URI.create(
            "https://api.render.com/v1/services/"
                + pathSegment(service)
                + "/deploys/"
                + pathSegment(deploy)),
        Map.of("Authorization", "Bearer " + key));
  }

  static Transport httpTransport() {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    return new Transport() {
      public Object json(URI uri, Map<String, String> headers)
          throws IOException, InterruptedException {
        var response =
            client.send(
                request(uri, "application/json", headers),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
          throw new HttpStatusFailure(response.statusCode());
        return FlatJson.parseValue(response.body());
      }

      public int status(URI uri) throws IOException, InterruptedException {
        return client
            .send(
                request(
                    uri,
                    "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                    Map.of()),
                HttpResponse.BodyHandlers.discarding())
            .statusCode();
      }
    };
  }

  private static HttpRequest request(URI uri, String accept, Map<String, String> headers) {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", accept)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .GET();
    headers.forEach(request::header);
    return request.build();
  }

  static void writeEvidence(Path path, Map<String, Object> payload) throws IOException {
    if (path != null) QualitySite.write(path, payload);
  }

  static String loadDeployId(Path path) throws IOException {
    if (path == null || !Files.isRegularFile(path)) return null;
    try {
      return deployId(FlatJson.parseValue(Files.readString(path)));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  static String pinnedHookUrl(String hook, String expected) {
    URI uri = URI.create(hook);
    var query = new LinkedHashMap<String, String>();
    if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
      for (String part : uri.getRawQuery().split("&")) {
        if (part.isEmpty()) continue;
        String[] pair = part.split("=", 2);
        query.put(
            URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
            pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
      }
    }
    query.put("ref", expected);
    String encoded =
        query.entrySet().stream()
            .map(entry -> formEncode(entry.getKey()) + "=" + formEncode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    String authority = uri.getRawAuthority() == null ? "" : "//" + uri.getRawAuthority();
    return (uri.getScheme() == null ? "" : uri.getScheme() + ":")
        + authority
        + (uri.getRawPath() == null ? "" : uri.getRawPath())
        + "?"
        + encoded
        + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
  }

  static String serviceId(String hook) {
    var match =
        Pattern.compile("(?:^|/)(srv-[A-Za-z0-9]+)(?:/|$)").matcher(URI.create(hook).getPath());
    return match.find() ? match.group(1) : "";
  }

  private static Path resolve(Path cwd, String path) {
    return path == null ? null : cwd.resolve(path);
  }

  private static String now(Clock clock) {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  private static String stripTrailingSlashes(String value) {
    return value.replaceAll("/+$", "");
  }

  private static String formEncode(String value) {
    // Keep Python urlencode's encoding of RFC 3986 unreserved characters.
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
  }

  private static String pathSegment(String value) {
    return formEncode(value).replace("+", "%20");
  }

  private static String required(Map<String, String> environment, String key) {
    if (!environment.containsKey(key))
      throw new IllegalArgumentException("Required delivery environment is absent");
    return environment.get(key);
  }

  private static String safeError(Exception exception) {
    if (exception instanceof HttpStatusFailure failure)
      return "Render/application verification HTTP error: HTTP " + failure.status;
    if (exception instanceof java.net.http.HttpTimeoutException)
      return "Deployment verification HTTP request timed out";
    if (exception instanceof IOException)
      return "Deployment verification transport or evidence I/O failed";
    return "Deployment verification input or response is invalid";
  }

  private static void finishEvidence(
      Map<String, Object> evidence,
      Clock clock,
      int attempts,
      String state,
      String result,
      String detail,
      String renderStatus) {
    evidence.putAll(
        QualitySite.map(
            "attempts",
            attempts,
            "endedAt",
            now(clock),
            "deploymentState",
            state,
            "result",
            result,
            "detail",
            detail,
            "renderDeployStatus",
            renderStatus));
  }

  private static final class HttpStatusFailure extends IOException {
    final int status;

    HttpStatusFailure(int status) {
      this.status = status;
    }
  }
}
