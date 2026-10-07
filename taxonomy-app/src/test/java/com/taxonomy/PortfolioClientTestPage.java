package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.shared.config.I18nConfig;
import com.taxonomy.shared.controller.I18nApiController;
import com.taxonomy.testsupport.BrowserSession;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * HTTP fixtures for JUnit-owned client contracts. Templates, scripts, translations
 * and the browser are real; each JUnit test owns its responses and assertions.
 * Backend persistence acceptance remains in {@link PortfolioUiAcceptanceIT}.
 */
final class PortfolioClientTestPage implements AutoCloseable {
    record Request(String method, String path, String query, String body) { }

    record Response(int status, String contentType, String body, Map<String, String> headers) {
        Response(int status, String contentType, String body) {
            this(status, contentType, body, Map.of());
        }

        Response {
            headers = Map.copyOf(headers);
        }

        Response withHeader(String name, String value) {
            var values = new LinkedHashMap<>(headers);
            values.put(name, value);
            return new Response(status, contentType, body, values);
        }
    }

    private record Page(String contextPath, String body) { }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BrowserSession browser;
    private final SpringTemplateEngine templates = new SpringTemplateEngine();
    private final I18nApiController translations;
    private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> translationCache = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Throwable> serverFailures = new CopyOnWriteArrayList<>();
    private volatile String contextPath = "";

    PortfolioClientTestPage(Path downloads) throws Exception {
        var messages = new I18nConfig().messageSource();
        translations = new I18nApiController(messages);
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        templates.setTemplateResolver(resolver);
        templates.setMessageSource(messages);

        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        try {
            browser = BrowserSession.open(server.getAddress().getPort(), downloads);
            driver().manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
            driver().manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        } catch (Exception | Error failure) {
            server.stop(0);
            executor.shutdownNow();
            throw failure;
        }
    }

    RemoteWebDriver driver() { return browser.driver(); }
    String origin() { return browser.origin(); }
    BrowserSession browser() { return browser; }

    void route(String method, String absolutePath, Function<Request, Response> handler) {
        routes.put(method.toUpperCase(Locale.ROOT) + " " + absolutePath, handler);
    }

    List<Request> requests() { return List.copyOf(requests); }
    void clearRequests() { requests.clear(); }
    void clearRoutes() { routes.clear(); }

    void open(String templateName, String prefix, String pagePath, String locale) {
        open(templateName, prefix, pagePath, locale, "");
    }

    void open(String templateName, String prefix, String pagePath, String locale,
              String bootstrapJavascript) {
        openAt(templateName, prefix, normalizedPrefix(prefix) + pagePath, locale, bootstrapJavascript);
    }

    void openAt(String templateName, String prefix, String absolutePagePath, String locale) {
        openAt(templateName, prefix, absolutePagePath, locale, "");
    }

    void openAt(String templateName, String prefix, String absolutePagePath, String locale,
                String bootstrapJavascript) {
        contextPath = normalizedPrefix(prefix);
        var servletContext = new MockServletContext();
        servletContext.setContextPath(contextPath);
        var request = new MockHttpServletRequest(servletContext);
        request.setContextPath(contextPath);
        request.setRequestURI(absolutePagePath);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(server.getAddress().getPort());
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.forLanguageTag(locale));
        context.setVariable("_csrf", null);
        String html = templates.process(templateName.replaceFirst("\\.html$", ""), context);
        if (!bootstrapJavascript.isEmpty()) {
            // Trusted, test-authored setup runs before the unchanged feature scripts.
            html = html.replace("</head>", "<script>" + bootstrapJavascript + "</script></head>");
        }
        pages.put(absolutePagePath, new Page(contextPath, html));
        driver().get(origin() + absolutePagePath + "?lang=" + locale);
    }

    void openHtml(String prefix, String pagePath, String html) {
        contextPath = normalizedPrefix(prefix);
        pages.put(contextPath + pagePath, new Page(contextPath, html));
        driver().get(origin() + contextPath + pagePath);
    }

    static Response json(int status, Object value) {
        return new Response(status, "application/json; charset=utf-8", json(value));
    }

    static String json(Object value) { return JSON.writeValueAsString(value); }

    private static String normalizedPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("/")) return "";
        if (!prefix.startsWith("/") || prefix.endsWith("/")) {
            throw new IllegalArgumentException("Expected a canonical application context: " + prefix);
        }
        return prefix;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var uri = exchange.getRequestURI();
            var request = new Request(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            Function<Request, Response> handler = routes.get(request.method() + " " + request.path());
            if (handler != null) {
                send(exchange, handler.apply(request));
                return;
            }
            Page page = pages.get(request.path());
            if (page != null) {
                send(exchange, new Response(200, "text/html; charset=utf-8", page.body()));
                return;
            }
            String path = request.path();
            if (!contextPath.isEmpty()) {
                if (!path.startsWith(contextPath + "/")) {
                    send(exchange, json(404, Map.of("error", "outside the configured application context")));
                    return;
                }
                path = path.substring(contextPath.length());
            }
            if (path.startsWith("/api/i18n/")) {
                String locale = path.substring("/api/i18n/".length());
                send(exchange, json(200, translationCache.computeIfAbsent(locale, translations::getTranslations)));
                return;
            }
            if (serveResource(exchange, path)) return;
            send(exchange, json(404, Map.of("error", "No fixture response for " + request.method() + " " + request.path())));
        } catch (Throwable failure) {
            serverFailures.add(failure);
        }
    }

    static boolean serveResource(HttpExchange exchange, String path) throws IOException {
        String classpath = resourcePath(path);
        if (classpath == null) {
            send(exchange, json(400, Map.of("error", "invalid fixture resource path")));
            return true;
        }
        var resource = new ClassPathResource(classpath);
        if (!resource.exists() || path.endsWith("/")) return false;
        byte[] body;
        try (var stream = resource.getInputStream()) { body = stream.readAllBytes(); }
        exchange.getResponseHeaders().set("Content-Type", contentType(path));
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        return true;
    }

    static String resourcePath(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0
                || List.of(path.split("/")).contains("..")) return null;
        boolean webjar = path.startsWith("/webjars/");
        String allowedPrefix = webjar ? "META-INF/resources/webjars/" : "static/";
        String candidate = webjar ? "META-INF/resources" + path : "static" + path;
        String normalized = new ClassPathResource(candidate).getPath();
        return normalized.startsWith(allowedPrefix) ? normalized : null;
    }

    private static String contentType(String path) {
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.status() == 204 || response.status() == 304) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        try { browser.close(); }
        finally {
            server.stop(0);
            executor.shutdownNow();
        }
        if (!serverFailures.isEmpty()) {
            var failure = new AssertionError("Portfolio HTTP fixture failed", serverFailures.getFirst());
            serverFailures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }
}
