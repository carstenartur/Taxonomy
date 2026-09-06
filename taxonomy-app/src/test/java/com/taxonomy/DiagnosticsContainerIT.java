package com.taxonomy;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Default HSQL diagnostics and browser-session lifecycle against the same application container. */
@Testcontainers
class DiagnosticsContainerIT extends AbstractDatabaseContainerIT {

    @Container
    static GenericContainer<?> app = ContainerTestUtils.appContainer();

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }

    @Test
    @Order(30)
    void browserSessionInventoryTracksTwoLoginsAndLogoutButNotBasicRequests() throws Exception {
        CookieManager firstCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieManager secondCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient basic = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
             HttpClient first = HttpClient.newBuilder().cookieHandler(firstCookies)
                     .connectTimeout(Duration.ofSeconds(5)).build();
             HttpClient second = HttpClient.newBuilder().cookieHandler(secondCookies)
                     .connectTimeout(Duration.ofSeconds(5)).build()) {
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
            var baseline = inventory(basic, authorization);
            assertThat(baseline.get("sessionCount").intValue()).isZero();
            assertThat(baseline.get("scope").textValue()).isEqualTo("LOCAL_INSTANCE");

            login(first, firstCookies);
            assertThat(inventory(basic, authorization).get("sessionCount").intValue()).isEqualTo(1);
            login(second, secondCookies);
            var both = inventory(second, null);
            assertThat(both.get("sessionCount").intValue()).isEqualTo(2);
            assertThat(both.get("userCount").intValue()).isEqualTo(1);
            assertThat(inventory(first, null).get("sessionCount").intValue()).isEqualTo(2);
            String json = both.toString();
            assertThat(json.contains(sessionId(firstCookies)) || json.contains(sessionId(secondCookies))).isFalse();
            assertThat(json).doesNotContain(ContainerTestUtils.TEST_ADMIN_PASSWORD, "password", "tokenValue");

            HttpResponse<String> page = exchange(second, "/admin/sessions?lang=de", null, null);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(page.body()).contains("Angemeldete Sitzungen", "sessionsRefresh");

            assertThat(exchange(first, "/logout", "_csrf=invalid", null).statusCode()).isEqualTo(403);
            assertThat(inventory(second, null).get("sessionCount").intValue()).isEqualTo(2);
            logout(first);
            assertThat(inventory(second, null).get("sessionCount").intValue()).isEqualTo(1);
            logout(second);
            assertThat(inventory(basic, authorization).get("sessionCount").intValue()).isZero();
            assertThat(exchange(basic, "/api/admin/sessions", null, null).statusCode()).isIn(401, 403);
        }
    }

    private static JsonNode inventory(HttpClient client, String authorization) throws Exception {
        HttpResponse<String> response = exchange(client, "/api/admin/sessions", null, authorization);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return JsonMapper.builder().build().readTree(response.body());
    }

    private static void login(HttpClient client, CookieManager cookies) throws Exception {
        HttpResponse<String> page = exchange(client, "/login", null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        String oldSession = sessionId(cookies);
        String form = "username=admin&password=" + encode(ContainerTestUtils.TEST_ADMIN_PASSWORD)
                + "&_csrf=" + encode(csrf(page.body()));
        assertThat(exchange(client, "/login", form, null).statusCode()).isIn(302, 303);
        assertThat(oldSession.equals(sessionId(cookies))).as("session fixation protection remains enabled").isFalse();
    }

    private static void logout(HttpClient client) throws Exception {
        HttpResponse<String> page = exchange(client, "/logout", null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(exchange(client, "/logout", "_csrf=" + encode(csrf(page.body())), null)
                .statusCode()).isIn(302, 303);
    }

    private static String csrf(String html) {
        var matcher = Pattern.compile("<input\\b(?=[^>]*\\bname=\"_csrf\")(?=[^>]*\\bvalue=\"([^\"]+)\")[^>]*>")
                .matcher(html);
        assertThat(matcher.find()).as("actual generated form contains a CSRF token").isTrue();
        return matcher.group(1);
    }

    private static String sessionId(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> exchange(HttpClient client, String path, String form,
                                                 String authorization) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://" + app.getHost() + ":"
                + app.getMappedPort(8080) + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", path.startsWith("/api/") ? "application/json" : "text/html");
        if (authorization != null) builder.header("Authorization", authorization);
        if (form == null) builder.GET();
        else builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
