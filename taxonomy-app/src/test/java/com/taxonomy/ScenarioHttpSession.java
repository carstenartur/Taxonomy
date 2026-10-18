package com.taxonomy;

import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** One regular form login for the scenario's HTTP client; failures never resubmit scenario actions. */
final class ScenarioHttpSession {
    private static final Pattern CSRF_INPUT = Pattern.compile(
            "<input\\b(?=[^>]*\\bname=\"_csrf\")(?=[^>]*\\bvalue=\"([^\"]+)\")[^>]*>");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final HttpClient http;
    private final CookieManager cookies;
    private final ObjectMapper json;
    private final String username;
    private final String password;
    private boolean attemptedLogin;
    private Session session;

    ScenarioHttpSession(HttpClient http, CookieManager cookies, ObjectMapper json, String username, String password) {
        this.http = http;
        this.cookies = cookies;
        this.json = json;
        this.username = username;
        this.password = password;
    }

    HttpRequest.Builder request(int port, String method, String path) throws Exception {
        assertThat(port).as("Scenario HTTP server port").isBetween(1, 65535);
        URI origin = URI.create("http://localhost:" + port);
        URI target = origin.resolve(path);
        sameOrigin(origin, target);
        Session authenticated = authenticated(origin);
        var request = builder(target);
        if (!SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            request.header("X-CSRF-TOKEN", authenticated.csrf());
        }
        return request;
    }

    // Status readers can start concurrently. Only one of them performs the real login.
    private synchronized Session authenticated(URI origin) throws Exception {
        if (session != null) {
            assertThat(session.origin()).as("The authenticated scenario server is unchanged").isEqualTo(origin);
            return session;
        }
        assertThat(attemptedLogin).as("A failed scenario login must remain visible, without an automatic retry").isFalse();
        attemptedLogin = true;
        String initialCsrf = readLoginCsrf(origin);
        String anonymousSession = sessionId(origin);
        String form = "username=" + encode(username) + "&password=" + encode(password) + "&_csrf=" + encode(initialCsrf);
        var login = http.send(builder(origin.resolve("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("Regular scenario form login").isEqualTo(302);
        URI destination = origin.resolve(login.headers().firstValue("Location").orElseThrow());
        sameOrigin(origin, destination);
        assertThat(destination.getPath()).as("Scenario form login must succeed").isNotEqualTo("/login");
        assertThat(sessionId(origin)).as("Form login retains production session fixation protection")
                .isNotEqualTo(anonymousSession);

        // Authentication rotates the CSRF token. Read its new value from the real login
        // page, then verify the lightweight authenticated-account endpoint explicitly.
        String csrf = readLoginCsrf(origin);
        var identity = http.send(builder(origin.resolve("/api/account/me"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(identity.statusCode()).as("Authenticated scenario account").isEqualTo(200);
        assertThat(json.readTree(identity.body()).path("username").asText()).isEqualTo(username);
        session = new Session(origin, csrf);
        return session;
    }

    private String readLoginCsrf(URI origin) throws Exception {
        var response = http.send(builder(origin.resolve("/login")).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("Scenario login page").isEqualTo(200);
        var match = CSRF_INPUT.matcher(response.body());
        assertThat(match.find()).as("The real login form supplies a CSRF token").isTrue();
        return match.group(1);
    }

    private String sessionId(URI origin) {
        var values = cookies.getCookieStore().get(origin).stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID") && !cookie.hasExpired())
                .map(HttpCookie::getValue).distinct().toList();
        assertThat(values).as("Exactly one scenario servlet-session cookie").hasSize(1);
        return values.getFirst();
    }

    private static HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sameOrigin(URI origin, URI target) {
        assertThat(target.getScheme()).as("Scenario request scheme").isEqualTo(origin.getScheme());
        assertThat(target.getRawAuthority()).as("Scenario requests remain on the authenticated local server")
                .isEqualTo(origin.getRawAuthority());
    }

    private record Session(URI origin, String csrf) { }
}
