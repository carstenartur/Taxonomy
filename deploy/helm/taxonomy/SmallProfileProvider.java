import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Disposable HTTPS peer. A real inference request stays in flight until its Pod is replaced. */
public final class SmallProfileProvider {
    public static void main(String[] args) throws Exception {
        Path keys = Path.of(args[0]);
        char[] password = Files.readString(keys.resolve("password")).toCharArray();
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys.resolve("server.p12"))) {
            store.load(input, password);
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress(Integer.parseInt(args[1])), 4);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newFixedThreadPool(3));
        var attempts = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                byte[] data = "{\"data\":[{\"id\":\"small-fixture\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())
                        || exchange.getRequestBody().readNBytes(1).length != 1) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                int count = attempts.incrementAndGet();
                Files.writeString(Path.of("/tmp/provider-attempts"), Integer.toString(count));
                if (count == 1) {
                    try {
                        Thread.sleep(90_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
    }
}
