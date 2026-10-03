package com.taxonomy.shared.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

/** Read-only behavior checks using the actual controller and runtime dependencies. */
public final class HelpResourceLifecycleAssertions {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static HelpController controller() { return new HelpController(new StaticMessageSource()); }

    public static void fallbackCache() throws Exception {
        var c = controller();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            for (String language : List.of("fr", "es", "it", "pt", "ja", "ko", "ru", "ar", "zh", "nl")) {
                LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
                var response = c.getDoc("USER_GUIDE");
                require(response.getStatusCode().value() == 200, "English fallback must remain available");
                require(response.getBody().contains("User Guide"), "Fallback must contain English text");
            }
            var field = HelpController.class.getDeclaredField("htmlCache");
            field.setAccessible(true);
            int size = ((Map<?, ?>) field.get(c)).size();
            require(size == 1, "One actual English resource must occupy one cache entry; observed " + size);
        } finally { LocaleContextHolder.setLocaleContext(previousLocale); }
    }

    public static void languageIsolation() throws Exception {
        var c = controller();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(Locale.GERMAN);
            String de = c.getDoc("USER_GUIDE").getBody();
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            String en = c.getDoc("USER_GUIDE").getBody();
            require(de.contains("Benutzerhandbuch"), "German doc must stay German");
            require(en.contains("User Guide"), "English doc must stay English");
            require(!de.equals(en), "Different actual resources must not share cached HTML");
        } finally { LocaleContextHolder.setLocaleContext(previousLocale); }
    }

    static void trackedImage(boolean failRead) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        URL resource = URL.of(URI.create("test-resource:/lifecycle.png"), new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override public void connect() { }
                    @Override public long getContentLengthLong() { return 3; }
                    @Override public InputStream getInputStream() {
                        opened.incrementAndGet();
                        return new InputStream() {
                            int index;
                            boolean wasClosed;
                            @Override public int read() throws IOException {
                                if (failRead) throw new IOException("deliberate small resource read failure");
                                return index++ < 3 ? index : -1;
                            }
                            @Override public void close() {
                                if (!wasClosed) closed.incrementAndGet();
                                wasClosed = true;
                            }
                        };
                    }
                };
            }
        });
        ClassLoader loader = new ClassLoader(previous) {
            @Override public URL getResource(String name) {
                return name.equals("docs/images/lifecycle.png") ? resource : super.getResource(name);
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(loader);
            var result = controller().getImage("lifecycle.png");
            require(result.getStatusCode().value() == (failRead ? 404 : 200), "Existing response policy changed");
            if (!failRead) require(Arrays.equals(result.getBody(), new byte[]{1, 2, 3}), "Image bytes changed");
            require(opened.get() > 0, "Test must open an actual tracked stream");
            require(opened.get() == closed.get(), "All opened streams must close; opened=" + opened + ", closed=" + closed);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    public static void imageSuccess() throws Exception { trackedImage(false); }
    public static void imageFailure() throws Exception { trackedImage(true); }
    public static void boundaries() {
        var c = controller();
        require(c.getDoc("../USER_GUIDE").getStatusCode().value() == 400, "Doc path validation");
        require(c.getDoc("NOT_A_DOCUMENT").getStatusCode().value() == 404, "Unknown doc allowlist");
        require(c.getImage("../image.png").getStatusCode().value() == 400, "Image traversal validation");
        require(c.getImage("does-not-exist.png").getStatusCode().value() == 404, "Missing image policy");
    }
    public static void missingResourceIsNotCached() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var c = controller();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
                @Override public URL getResource(String name) {
                    return name.startsWith("docs/") ? null : super.getResource(name);
                }
            });
            require(c.getDoc("USER_GUIDE").getStatusCode().value() == 404, "Missing document must be 404");
        } finally { Thread.currentThread().setContextClassLoader(previous); }
        require(c.getDoc("USER_GUIDE").getStatusCode().value() == 200, "Missing resource must not poison the cache");
    }
    public static void main(String[] args) throws Exception {
        int failed = 0;
        for (String name : List.of("fallbackCache", "languageIsolation", "imageSuccess", "imageFailure", "boundaries", "missingResourceIsNotCached")) {
            try { HelpResourceLifecycleAssertions.class.getMethod(name).invoke(null); System.out.println("PASS " + name); }
            catch (java.lang.reflect.InvocationTargetException e) {
                failed++; System.out.println("FAIL " + name + ": " + e.getCause());
            }
        }
        System.out.println("RESULT tests=6 failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}
