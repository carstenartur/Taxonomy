package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Prevents new browser transport calls outside the central JavaScript API layer.
 *
 * <p>Executable native transport references and aliases are checked against an explicit bounded
 * infrastructure ledger and the Git baseline. Named domain clients do not acquire native transport.
 * Inline scripts in every HTML template use the same policy and retain source line diagnostics.
 */
final class FrontendApiBoundaryPolicy {

    private static final String STATIC_JS = "taxonomy-app/src/main/resources/static/js";
    private static final String TEMPLATES = "taxonomy-app/src/main/resources/templates";
    private static final String CANONICAL_TRANSPORT = "api/taxonomy-api-client.js";
    private static final Map<String, TransportException> INFRASTRUCTURE_EXCEPTIONS =
            Map.ofEntries(
                    Map.entry(
                            "taxonomy-i18n.js",
                            new TransportException(
                                    4,
                                    List.of("/api/i18n"),
                                    "Application-prefix and locale bootstrap before the canonical"
                                            + " client loads",
                                    "Remove when locale bootstrap and prefix capture move into the"
                                            + " canonical transport")),
                    Map.entry(
                            "core/taxonomy-analysis-session-core.js",
                            new TransportException(
                                    8,
                                    List.of(),
                                    "Shared legacy workspace routing and native EventSource"
                                            + " reconnect adapter",
                                    "Remove native compatibility routing after the last legacy"
                                            + " transport consumer is retired")),
                    Map.entry(
                            "core/taxonomy-analysis-session-transport.js",
                            new TransportException(
                                    2,
                                    List.of(),
                                    "The same analysis-generation factory protects legacy and named"
                                            + " transports",
                                    "Remove the native compatibility installer when all external"
                                            + " legacy consumers use named clients")),
                    Map.entry(
                            "core/taxonomy-operation-coordinator.js",
                            new TransportException(
                                    4,
                                    List.of(),
                                    "The same durable-operation factory protects legacy and named"
                                            + " transports",
                                    "Remove legacy global installer when remaining integration"
                                            + " consumers use the canonical registry")),
                    Map.entry(
                            "portfolio/taxonomy-portfolio-async.js",
                            new TransportException(
                                    2,
                                    List.of(),
                                    "The same admitted portfolio-job factory is registered for"
                                            + " legacy and named requests",
                                    "Remove native compatibility capture after legacy portfolio"
                                            + " consumers are retired")),
                    Map.entry(
                            "shared/architecture-workflow.js",
                            new TransportException(
                                    3,
                                    List.of(),
                                    "Shared exact-source routing factory is registered for legacy"
                                            + " and named requests",
                                    "Remove native installer after legacy workflow page consumers"
                                            + " are retired")),
                    Map.entry(
                            "core/taxonomy-state.js",
                            new TransportException(
                                    9,
                                    List.of(),
                                    "SSE facade cancels obsolete analysis sessions while retaining"
                                            + " native event protocol",
                                    "Remove when the complete session stream facade moves into the"
                                            + " named SSE adapter")),
                    Map.entry(
                            "core/taxonomy-scoring.js",
                            new TransportException(
                                    1,
                                    List.of("/api/analyze-stream"),
                                    "Scoring SSE events have selection/recovery fences and explicit"
                                            + " close ownership",
                                    "Remove when the complete scoring stream lifecycle moves into"
                                            + " the named SSE adapter")),
                    Map.entry(
                            "api/analysis-session-api.js",
                            new TransportException(
                                    2,
                                    List.of("/api/analysis-runs"),
                                    "Named SSE adapter retains browser Last-Event-ID reconnect and"
                                            + " immutable workspace identity",
                                    "Replace only with an equivalent named streaming lifecycle; do"
                                            + " not buffer SSE as JSON")),
                    Map.entry(
                            "shared/taxonomy-help.js",
                            new TransportException(
                                    2,
                                    List.of(),
                                    "Read-only same-origin public /help HTML documents, independent"
                                            + " of workspace state",
                                    "Remove when a named HTML documentation client owns this"
                                            + " lifecycle")),
                    Map.entry(
                            "shared/taxonomy-about.js",
                            new TransportException(
                                    1,
                                    List.of(),
                                    "Read-only public notices asset; authenticated About data uses"
                                            + " its named client",
                                    "Remove when static notice downloads use a named asset"
                                            + " reader")));

    private record TransportException(
            int maxReferences, List<String> apiPrefixes, String reason, String removalCondition) {}

    private static final Set<String> LEGACY_ALLOWLIST = Set.of();

    Inspection inspect(Path repositoryRoot, String baseRef, RevisionTextReader revisionReader) {
        if (baseRef == null || baseRef.isBlank()) {
            throw new IllegalArgumentException("frontend API baseline ref must not be blank");
        }
        Path root = repositoryRoot.toAbsolutePath().normalize();
        CurrentScan current = scanCurrent(root);
        Map<String, Integer> baseline =
                baselineCounts(baseRef, current.fetchCounts().keySet(), revisionReader);
        return evaluate(current, baseline, baseRef);
    }

    CurrentScan scanCurrent(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path jsRoot = root.resolve(STATIC_JS);
        if (!Files.isDirectory(jsRoot)) {
            throw new IllegalArgumentException("frontend JavaScript root is missing: " + jsRoot);
        }

        Map<String, Integer> fetchCounts = new TreeMap<>();
        Map<String, Integer> legacyApiInventory = new TreeMap<>();
        List<String> fixedInventoryViolations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(jsRoot)) {
            files =
                    stream.filter(Files::isRegularFile)
                            .filter(FrontendApiBoundaryPolicy::isJavaScript)
                            .sorted()
                            .toList();
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot inspect frontend JavaScript below " + jsRoot, error);
        }

        for (Path path : files) {
            String relative = jsRoot.relativize(path).toString().replace('\\', '/');
            String text = readUtf8(path);
            TransportUse use = transports(text);
            int fetchCount = use.references();
            fetchCounts.put(relative, fetchCount);
            List<Integer> apiLines =
                    use.apiCalls().stream()
                            .map(call -> line(text, call.offset()))
                            .distinct()
                            .toList();
            TransportException exception = INFRASTRUCTURE_EXCEPTIONS.get(relative);
            if (Set.of(
                                    "core/taxonomy-state.js",
                                    "core/taxonomy-scoring.js",
                                    "api/analysis-session-api.js")
                            .contains(relative)
                    && transports(text, Set.of("fetch", "XMLHttpRequest")).references() > 0) {
                fixedInventoryViolations.add(
                        relative
                                + ": EventSource-only infrastructure cannot acquire ordinary HTTP"
                                + " transport");
            }
            if (exception != null && fetchCount > exception.maxReferences())
                fixedInventoryViolations.add(
                        relative
                                + ": infrastructure native-reference budget increased to "
                                + fetchCount
                                + " (maximum "
                                + exception.maxReferences()
                                + ")");
            if (exception != null) {
                apiLines =
                        use.apiCalls().stream()
                                .filter(
                                        call ->
                                                exception.apiPrefixes().stream()
                                                        .noneMatch(
                                                                prefix ->
                                                                        call.url().equals(prefix)
                                                                                || call.url()
                                                                                        .startsWith(
                                                                                                prefix
                                                                                                        + "/")
                                                                                || call.url()
                                                                                        .startsWith(
                                                                                                prefix
                                                                                                        + "?")))
                                .map(call -> line(text, call.offset()))
                                .distinct()
                                .toList();
                if (!use.apiCalls().isEmpty())
                    legacyApiInventory.put(relative, use.apiCalls().size());
                if (!apiLines.isEmpty())
                    fixedInventoryViolations.add(
                            relative + ": direct /api fetch at lines " + apiLines);
                continue;
            }
            if (apiLines.isEmpty() || relative.equals(CANONICAL_TRANSPORT)) {
                continue;
            }
            if (LEGACY_ALLOWLIST.contains(relative)) {
                legacyApiInventory.put(relative, apiLines.size());
            } else {
                fixedInventoryViolations.add(relative + ": direct /api fetch at lines " + apiLines);
            }
        }

        Path templateRoot = root.resolve(TEMPLATES);
        if (Files.isDirectory(templateRoot)) {
            try (Stream<Path> templates = Files.walk(templateRoot)) {
                for (Path template :
                        templates
                                .filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".html"))
                                .sorted()
                                .toList()) {
                    String key =
                            "templates/"
                                    + templateRoot
                                            .relativize(template)
                                            .toString()
                                            .replace('\\', '/');
                    String text = inlineScripts(readUtf8(template));
                    TransportUse use = transports(text);
                    fetchCounts.put(key, use.references());
                    List<Integer> lines =
                            use.apiCalls().stream()
                                    .map(call -> line(text, call.offset()))
                                    .distinct()
                                    .toList();
                    if (!lines.isEmpty()) {
                        legacyApiInventory.put(key, lines.size());
                        fixedInventoryViolations.add(key + ": direct /api fetch at lines " + lines);
                    }
                }
            } catch (IOException error) {
                throw new IllegalArgumentException(
                        "cannot inspect frontend templates below " + templateRoot, error);
            }
        }

        Set<String> reducible = new TreeSet<>(LEGACY_ALLOWLIST);
        reducible.removeAll(legacyApiInventory.keySet());
        return new CurrentScan(
                Collections.unmodifiableMap(fetchCounts),
                Collections.unmodifiableMap(legacyApiInventory),
                List.copyOf(fixedInventoryViolations),
                Collections.unmodifiableSet(reducible));
    }

    Inspection evaluate(CurrentScan current, Map<String, Integer> baselineCounts, String baseRef) {
        List<String> failures = new ArrayList<>(current.fixedInventoryViolations());
        Map<String, Integer> currentDebt = debtOnly(current.fetchCounts());
        Map<String, Integer> baselineDebt = debtOnly(baselineCounts);

        for (Map.Entry<String, Integer> entry : currentDebt.entrySet()) {
            int previous = baselineDebt.getOrDefault(entry.getKey(), 0);
            if (previous == 0) {
                failures.add(
                        entry.getKey()
                                + ": introduces "
                                + entry.getValue()
                                + " direct fetch() call(s); use static/js/api instead");
            } else if (entry.getValue() > previous) {
                failures.add(
                        entry.getKey()
                                + ": direct fetch() count increased from "
                                + previous
                                + " to "
                                + entry.getValue());
            }
        }

        int currentTotal = currentDebt.values().stream().mapToInt(Integer::intValue).sum();
        int baselineTotal = baselineDebt.values().stream().mapToInt(Integer::intValue).sum();
        if (currentTotal > baselineTotal) {
            failures.add(
                    "legacy direct fetch() debt increased from "
                            + baselineTotal
                            + " to "
                            + currentTotal);
        }

        StringBuilder report =
                new StringBuilder("Frontend API boundary\n\n")
                        .append("Baseline: ")
                        .append(baseRef)
                        .append('\n')
                        .append("Legacy direct fetch debt: ")
                        .append(currentTotal)
                        .append(" call(s) in ")
                        .append(currentDebt.size())
                        .append(" file(s); baseline ")
                        .append(baselineTotal)
                        .append(" call(s) in ")
                        .append(baselineDebt.size())
                        .append(" file(s).\n\n")
                        .append("Legacy literal /api inventory:\n");
        current.legacyApiInventory()
                .forEach(
                        (path, count) ->
                                report.append("- ")
                                        .append(path)
                                        .append(": ")
                                        .append(count)
                                        .append('\n'));
        report.append("\nBounded infrastructure/streaming exceptions:\n");
        INFRASTRUCTURE_EXCEPTIONS.forEach(
                (path, exception) -> {
                    if (current.fetchCounts().containsKey(path))
                        report.append("- ")
                                .append(path)
                                .append(": ")
                                .append(current.fetchCounts().get(path))
                                .append("/")
                                .append(exception.maxReferences())
                                .append(" native references; ")
                                .append(exception.reason())
                                .append(". Removal: ")
                                .append(exception.removalCondition())
                                .append('\n');
                });
        if (!current.reducibleAllowlist().isEmpty()) {
            report.append("\nAllowlist entries ready for removal:\n");
            current.reducibleAllowlist()
                    .forEach(path -> report.append("- ").append(path).append('\n'));
        }
        if (!failures.isEmpty()) {
            report.append("\nViolations:\n");
            failures.forEach(failure -> report.append("- ").append(failure).append('\n'));
        }
        report.append("\nResult: ").append(failures.isEmpty() ? "PASS\n" : "FAIL\n");
        return new Inspection(
                failures.isEmpty(),
                List.copyOf(failures),
                currentTotal,
                baselineTotal,
                report.toString());
    }

    void writeReport(Path output, String report) {
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, report, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot write frontend API boundary report " + output, error);
        }
    }

    private static String inlineScripts(String html) {
        char[] masked = html.toCharArray();
        for (int i = 0; i < masked.length; i++)
            if (masked[i] != '\n' && masked[i] != '\r') masked[i] = ' ';
        var matcher =
                java.util.regex.Pattern.compile(
                                "(?is)<script\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>(.*?)</script\\s*>")
                        .matcher(html);
        while (matcher.find())
            html.getChars(matcher.start(1), matcher.end(1), masked, matcher.start(1));
        return new String(masked);
    }

    /** Counts executable native acquisitions and their statically resolvable aliases. */
    static int countDirectFetch(String text) {
        return transports(text).references();
    }

    private static TransportUse transports(String text) {
        return transports(text, NATIVE_TRANSPORTS);
    }

    private static TransportUse transports(String text, Set<String> nativeTransports) {
        List<JsToken> tokens = new JsLexer(text).tokens();
        Set<String> globals = new TreeSet<>(Set.of("window", "globalThis", "self", "global"));
        Set<String> aliases = new TreeSet<>();
        Map<String, String> strings = new TreeMap<>();
        Set<Integer> destructured = new TreeSet<>();
        boolean changed;
        do {
            int previous = globals.size() + aliases.size() + strings.size() + destructured.size();
            for (int i = 1; i < tokens.size(); i++) {
                if (!value(tokens, i).equals("=")) continue;
                String left = value(tokens, i - 1);
                if (identifier(left)) {
                    int right = i + 1;
                    while (value(tokens, right).equals("(")) right++;
                    if (globals.contains(value(tokens, right))
                            && Set.of(";", ",", ")", "").contains(value(tokens, right + 1))) {
                        globals.add(left);
                    }
                    Literal literal = literal(tokens, i + 1, strings);
                    if (literal != null
                            && Set.of(";", ",", ")", "").contains(value(tokens, literal.end()))) {
                        strings.putIfAbsent(left, literal.text());
                    }
                    Ref ref =
                            reference(
                                    tokens,
                                    i + 1,
                                    globals,
                                    aliases,
                                    strings,
                                    destructured,
                                    nativeTransports);
                    if (ref != null) {
                        String next = value(tokens, ref.end());
                        boolean invoked =
                                next.equals("(")
                                        || next.equals("?.")
                                                && value(tokens, ref.end() + 1).equals("(");
                        boolean member = next.equals(".") || next.equals("?.");
                        if (!invoked && (!member || value(tokens, ref.end() + 1).equals("bind")))
                            aliases.add(left);
                    }
                } else if (left.equals("}")) {
                    int open = matchingBack(tokens, i - 1, "{", "}");
                    int right = i + 1;
                    while (value(tokens, right).equals("(")) right++;
                    if (open >= 0 && globals.contains(value(tokens, right))) {
                        for (int at = open + 1; at < i - 1; at++) {
                            if (!nativeTransports.contains(value(tokens, at))) continue;
                            int end = at + 1;
                            String binding = value(tokens, at);
                            if (value(tokens, end).equals(":")) binding = value(tokens, end + 1);
                            if (identifier(binding)) {
                                aliases.add(binding);
                                destructured.add(at);
                            }
                        }
                    }
                }
            }
            changed =
                    previous
                            != globals.size()
                                    + aliases.size()
                                    + strings.size()
                                    + destructured.size();
        } while (changed);

        int references = 0;
        List<ApiCall> api = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Ref ref =
                    reference(tokens, i, globals, aliases, strings, destructured, nativeTransports);
            if (ref == null) continue;
            int end = ref.end();
            String next = value(tokens, end);
            boolean member =
                    next.equals(".") || next.equals("?.") && !value(tokens, end + 1).equals("(");
            String operation = member ? value(tokens, end + 1) : "";
            // A marker such as fetch.__taxonomyWorkspaceRouting is observation, not acquisition.
            if (member && !Set.of("call", "apply", "bind").contains(operation)) {
                i = end - 1;
                continue;
            }
            boolean invoked =
                    next.equals("(")
                            || next.equals("?.") && value(tokens, end + 1).equals("(")
                            || member && value(tokens, end + 2).equals("(");
            boolean assignment =
                    value(tokens, i - 1).equals("=") || value(tokens, i - 1).equals("return");
            boolean declaration =
                    Set.of("const", "let", "var", "function").contains(value(tokens, i - 1))
                            || next.equals("=");
            if (ref.nativeTransport() || !declaration && (invoked || assignment)) {
                if (!value(tokens, i - 1).equals("typeof")) references++;
                int argument = member ? end + 3 : next.equals("?.") ? end + 2 : end + 1;
                if (member) { // call/apply/bind receive the receiver before the endpoint.
                    while (argument < tokens.size()
                            && !Set.of(",", ")").contains(value(tokens, argument))) argument++;
                    argument++;
                    if (operation.equals("apply") && value(tokens, argument).equals("["))
                        argument++;
                }
                if (invoked
                        && argument < tokens.size()
                        && tokens.get(argument).literal()
                        && tokens.get(argument).text().startsWith("/api/")) {
                    api.add(new ApiCall(tokens.get(i).offset(), tokens.get(argument).text()));
                }
            }
            i = end - 1;
        }
        return new TransportUse(references, List.copyOf(api));
    }

    private static final Set<String> NATIVE_TRANSPORTS =
            Set.of("fetch", "EventSource", "XMLHttpRequest");

    private static Ref reference(
            List<JsToken> tokens,
            int start,
            Set<String> globals,
            Set<String> aliases,
            Map<String, String> strings,
            Set<Integer> destructured,
            Set<String> nativeTransports) {
        if (start < 0 || start >= tokens.size()) return null;
        if (destructured.contains(start)) return new Ref(start + 1, true);
        if (value(tokens, start).equals("(")) {
            Ref inner =
                    reference(
                            tokens,
                            start + 1,
                            globals,
                            aliases,
                            strings,
                            destructured,
                            nativeTransports);
            return inner != null && value(tokens, inner.end()).equals(")")
                    ? new Ref(inner.end() + 1, inner.nativeTransport())
                    : null;
        }
        if (tokens.get(start).literal() || Set.of(".", "?.").contains(value(tokens, start - 1)))
            return null;
        String name = value(tokens, start);
        if (nativeTransports.contains(name) && !value(tokens, start + 1).equals(":"))
            return new Ref(start + 1, true);
        if (globals.contains(name)) {
            int end = start + 1;
            String property = null;
            if (Set.of(".", "?.").contains(value(tokens, end))) {
                property = value(tokens, end + 1);
                end += 2;
            } else if (value(tokens, end).equals("[")) {
                Literal key = literal(tokens, end + 1, strings);
                if (key != null && value(tokens, key.end()).equals("]")) {
                    property = key.text();
                    end = key.end() + 1;
                }
            }
            if (property != null && nativeTransports.contains(property)) return new Ref(end, true);
            if (property != null && aliases.contains(property)) return new Ref(end, false);
        }
        if (aliases.contains(name)) return new Ref(start + 1, false);
        return null;
    }

    private static Literal literal(List<JsToken> tokens, int start, Map<String, String> strings) {
        StringBuilder value = new StringBuilder();
        int cursor = start;
        boolean found = false;
        while (cursor < tokens.size()) {
            JsToken token = tokens.get(cursor);
            String part = token.literal() ? token.text() : strings.get(token.text());
            if (part == null) return found ? new Literal(value.toString(), cursor) : null;
            value.append(part);
            found = true;
            cursor++;
            if (!value(tokens, cursor).equals("+")) break;
            cursor++;
        }
        return found ? new Literal(value.toString(), cursor) : null;
    }

    private static int matchingBack(List<JsToken> tokens, int close, String open, String closing) {
        int depth = 0;
        for (int cursor = close; cursor >= 0; cursor--) {
            if (value(tokens, cursor).equals(closing)) depth++;
            if (value(tokens, cursor).equals(open) && --depth == 0) return cursor;
        }
        return -1;
    }

    private static String value(List<JsToken> tokens, int at) {
        return at < 0 || at >= tokens.size() ? "" : tokens.get(at).text();
    }

    private static boolean identifier(String text) {
        return !text.isEmpty()
                && Character.isJavaIdentifierStart(text.charAt(0))
                && text.chars().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    private record Ref(int end, boolean nativeTransport) {}

    private record Literal(String text, int end) {}

    private record ApiCall(int offset, String url) {}

    private record TransportUse(int references, List<ApiCall> apiCalls) {}

    private record JsToken(String text, int offset, boolean literal) {}

    /** Lexical boundaries only; aliases are resolved above without executing application code. */
    private static final class JsLexer {
        private final String source;
        private final List<JsToken> result = new ArrayList<>();
        private int cursor;

        JsLexer(String source) {
            this.source = source;
        }

        List<JsToken> tokens() {
            code(false);
            return result;
        }

        private void code(boolean interpolation) {
            int braces = 0;
            while (cursor < source.length()) {
                char ch = source.charAt(cursor);
                if (Character.isWhitespace(ch)) {
                    cursor++;
                    continue;
                }
                if (source.startsWith("//", cursor)) {
                    skipLineComment(2);
                    continue;
                }
                if (source.startsWith("/*", cursor)) {
                    skipTo("*/", 2);
                    continue;
                }
                if (source.startsWith("<!--", cursor)) {
                    skipLineComment(4);
                    continue;
                }
                if (source.startsWith("-->", cursor) && startsLine()) {
                    skipLineComment(3);
                    continue;
                }
                if (ch == '\'' || ch == '"') {
                    quoted(ch);
                    continue;
                }
                if (ch == '`') {
                    template();
                    continue;
                }
                if (ch == '/' && regexStart()) {
                    regex();
                    continue;
                }
                if (ch == '}' && interpolation && braces == 0) {
                    result.add(new JsToken("}", cursor++, false));
                    return;
                }
                int start = cursor;
                if (Character.isJavaIdentifierStart(ch)) {
                    cursor++;
                    while (cursor < source.length()
                            && Character.isJavaIdentifierPart(source.charAt(cursor))) cursor++;
                    result.add(new JsToken(source.substring(start, cursor), start, false));
                    continue;
                }
                String operator = null;
                for (String candidate :
                        List.of(
                                "===", "!==", "=>", "?.", "==", "!=", "&&", "||", "??", "+=", "-=",
                                "++", "--"))
                    if (source.startsWith(candidate, cursor)) {
                        operator = candidate;
                        break;
                    }
                if (operator != null) {
                    result.add(new JsToken(operator, cursor, false));
                    cursor += operator.length();
                    continue;
                }
                if (ch == '{') braces++;
                if (ch == '}') braces--;
                result.add(new JsToken(String.valueOf(ch), cursor++, false));
            }
        }

        private static boolean lineTerminator(char ch) {
            return ch == '\n' || ch == '\r' || ch == 0x2028 || ch == 0x2029;
        }

        private void skipLineComment(int prefix) {
            cursor += prefix;
            while (cursor < source.length() && !lineTerminator(source.charAt(cursor))) cursor++;
        }

        private boolean startsLine() {
            for (int i = cursor - 1; i >= 0; i--) {
                char ch = source.charAt(i);
                if (lineTerminator(ch)) return true;
                if (!Character.isWhitespace(ch)) return false;
            }
            return true;
        }

        private void skipTo(String marker, int prefix) {
            int end = source.indexOf(marker, cursor + prefix);
            cursor = end < 0 ? source.length() : end + marker.length();
        }

        private void quoted(char quote) {
            int start = cursor++;
            StringBuilder text = new StringBuilder();
            while (cursor < source.length()) {
                char ch = source.charAt(cursor++);
                if (ch == quote) break;
                if (ch == '\\') text.append(escape());
                else text.append(ch);
            }
            result.add(new JsToken(text.toString(), start, true));
        }

        private String escape() {
            if (cursor >= source.length()) return "";
            char ch = source.charAt(cursor++);
            if (ch == '\n') return "";
            int digits = ch == 'u' ? 4 : ch == 'x' ? 2 : 0;
            if (digits > 0 && cursor + digits <= source.length())
                try {
                    String value =
                            String.valueOf(
                                    (char)
                                            Integer.parseInt(
                                                    source.substring(cursor, cursor + digits), 16));
                    cursor += digits;
                    return value;
                } catch (NumberFormatException ignored) {
                    /* Retain invalid text for fail-closed inventory. */
                }
            return switch (ch) {
                case 'n' -> "\n";
                case 'r' -> "\r";
                case 't' -> "\t";
                default -> String.valueOf(ch);
            };
        }

        private void template() {
            int start = cursor++;
            StringBuilder text = new StringBuilder();
            while (cursor < source.length()) {
                char ch = source.charAt(cursor++);
                if (ch == '`') {
                    result.add(new JsToken(text.toString(), start, true));
                    return;
                }
                if (ch == '\\') {
                    text.append(escape());
                    continue;
                }
                if (ch == '$' && cursor < source.length() && source.charAt(cursor) == '{') {
                    result.add(new JsToken(text.toString(), start, true));
                    text.setLength(0);
                    result.add(new JsToken("${", cursor - 1, false));
                    cursor++;
                    code(true);
                    start = cursor;
                } else text.append(ch);
            }
            result.add(new JsToken(text.toString(), start, true));
        }

        private boolean regexStart() {
            if (result.isEmpty()) return true;
            return Set.of(
                            "=", "(", "[", "{", ",", ":", ";", "!", "&&", "||", "?", "return",
                            "throw", "case", "=>")
                    .contains(result.getLast().text());
        }

        private void regex() {
            cursor++;
            boolean bracket = false;
            while (cursor < source.length()) {
                char ch = source.charAt(cursor++);
                if (ch == '\\') {
                    if (cursor < source.length()) cursor++;
                    continue;
                }
                if (ch == '[') bracket = true;
                if (ch == ']') bracket = false;
                if (ch == '/' && !bracket) break;
            }
            while (cursor < source.length() && Character.isLetter(source.charAt(cursor))) cursor++;
        }
    }

    static boolean isTransportOwner(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.equals(CANONICAL_TRANSPORT)
                || INFRASTRUCTURE_EXCEPTIONS.containsKey(normalized);
    }

    private Map<String, Integer> baselineCounts(
            String baseRef, Set<String> currentPaths, RevisionTextReader reader) {
        Set<String> comparisonPaths = new TreeSet<>(currentPaths);
        String prefix = STATIC_JS + "/";
        for (String repositoryPath : reader.paths(baseRef, STATIC_JS)) {
            String normalized = repositoryPath.replace('\\', '/');
            if (!normalized.startsWith(prefix)) {
                continue;
            }
            String relative = normalized.substring(prefix.length());
            if (isJavaScript(relative)) {
                comparisonPaths.add(relative);
            }
        }
        for (String path : reader.paths(baseRef, TEMPLATES)) {
            String normalized = path.replace('\\', '/');
            if (normalized.startsWith(TEMPLATES + "/") && normalized.endsWith(".html")) {
                comparisonPaths.add("templates/" + normalized.substring(TEMPLATES.length() + 1));
            }
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (String relative : comparisonPaths) {
            if (isTransportOwner(relative)) {
                continue;
            }
            Optional<String> text = reader.read(baseRef, repositoryPath(relative));
            if (text.isEmpty()) {
                continue;
            }
            counts.put(
                    relative,
                    countDirectFetch(
                            relative.startsWith("templates/")
                                    ? inlineScripts(text.get())
                                    : text.get()));
        }
        return counts;
    }

    private static String repositoryPath(String comparisonPath) {
        return comparisonPath.startsWith("templates/")
                ? TEMPLATES + comparisonPath.substring("templates".length())
                : STATIC_JS + "/" + comparisonPath;
    }

    private static Map<String, Integer> debtOnly(Map<String, Integer> counts) {
        Map<String, Integer> result = new TreeMap<>();
        counts.forEach(
                (path, count) -> {
                    if (!isTransportOwner(path) && count > 0) {
                        result.put(path, count);
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private static int line(String source, int offset) {
        return 1 + (int) source.substring(0, offset).chars().filter(value -> value == '\n').count();
    }

    private static boolean isJavaScript(Path path) {
        return isJavaScript(path.getFileName().toString());
    }

    private static boolean isJavaScript(String name) {
        return name.endsWith(".js") || name.endsWith(".mjs");
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read " + path, error);
        }
    }

    @FunctionalInterface
    interface RevisionTextReader {
        Optional<String> read(String revision, String repositoryPath);

        default Set<String> paths(String revision, String repositoryPathPrefix) {
            return Set.of();
        }
    }

    record CurrentScan(
            Map<String, Integer> fetchCounts,
            Map<String, Integer> legacyApiInventory,
            List<String> fixedInventoryViolations,
            Set<String> reducibleAllowlist) {}

    record Inspection(
            boolean passed,
            List<String> failures,
            int currentDebt,
            int baselineDebt,
            String report) {}
}
