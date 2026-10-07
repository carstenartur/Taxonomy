package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast real-HTTP boundary checks without constructing the browser fixture. */
class PortfolioClientTestPageTest {
    @Test
    void decodedPathsCannotEscapeStaticOrWebjarResources() throws Exception {
        HttpServer server = resourceServer();
        try (var client = HttpClient.newHttpClient()) {
            for (String path : List.of("/%5c..%5c..%5capplication.properties",
                    "/webjars/%5c..%5c..%5c..%5c..%5capplication.properties",
                    "/%2e%2e/application.properties", "/webjars/%2e%2e/%2e%2e/application.properties")) {
                var response = get(client, server, path);
                assertThat(response.statusCode()).as(path).isEqualTo(400);
            }
            assertThat(PortfolioClientTestPage.resourcePath("application.properties")).isNull();
            assertThat(PortfolioClientTestPage.resourcePath("/../application.properties")).isNull();
        } finally { server.stop(0); }
    }

    @Test
    void realStaticScriptsAndBootstrapKeepTheirHttpBytesAndContentTypes() throws Exception {
        HttpServer server = resourceServer();
        try (var client = HttpClient.newHttpClient()) {
            var script = get(client, server, "/js/api/portfolio-api.js");
            assertThat(script.statusCode()).isEqualTo(200);
            assertThat(script.headers().firstValue("Content-Type")).contains("text/javascript; charset=utf-8");
            assertThat(script.body()).contains("TaxonomyPortfolioApi");
            var bootstrap = get(client, server, "/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css");
            assertThat(bootstrap.statusCode()).isEqualTo(200);
            assertThat(bootstrap.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
            assertThat(bootstrap.body().substring(0, 250)).contains("Bootstrap", "v5.3.8");
            assertThat(get(client, server, "/not-a-real-static-file.js").statusCode()).isEqualTo(404);
        } finally { server.stop(0); }
    }

    private static HttpServer resourceServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!PortfolioClientTestPage.serveResource(exchange, exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.start();
        return server;
    }

    private static HttpResponse<String> get(HttpClient client, HttpServer server, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
