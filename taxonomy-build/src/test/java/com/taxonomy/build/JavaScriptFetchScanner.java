package com.taxonomy.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates executable JavaScript {@code fetch(...)} calls without counting text in
 * comments, quoted strings, regular-expression literals, or template-literal text.
 * Executable expressions inside {@code ${...}} template substitutions remain visible.
 */
final class JavaScriptFetchScanner {

    private static final Pattern FETCH_CALL = Pattern.compile(
            "(?<![A-Za-z0-9_$])fetch\\s*(?:\\?\\.\\s*)?\\(");
    private static final Set<String> REGEX_PREFIX_KEYWORDS = Set.of(
            "await", "case", "delete", "do", "else", "in", "instanceof",
            "new", "of", "return", "throw", "typeof", "void", "yield");
    private static final char VALUE_SENTINEL = '0';

    private JavaScriptFetchScanner() {
    }

    static List<FetchCall> scan(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String executable = maskNonExecutableText(source);
        Matcher matcher = FETCH_CALL.matcher(executable);
        List<FetchCall> result = new ArrayList<>();
        while (matcher.find()) {
            if (isNamedFunctionDeclaration(executable, matcher.start())) {
                continue;
            }
            int openingParenthesis = executable.lastIndexOf('(', matcher.end() - 1);
            result.add(new FetchCall(
                    lineNumber(source, matcher.start()),
                    beginsWithDirectApiLiteral(source, openingParenthesis + 1)));
        }
        return List.copyOf(result);
    }

    private static String maskNonExecutableText(String source) {
        char[] masked = source.toCharArray();
        scanCode(source, masked, 0, false);
        return new String(masked);
    }

    /**
     * Scans executable code. For a template expression, the scan returns just after
     * its matching closing brace while preserving nested executable code.
     */
    private static int scanCode(
            String source,
            char[] masked,
            int index,
            boolean templateExpression) {
        int braceDepth = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (templateExpression && current == '}') {
                if (braceDepth == 0) {
                    mask(masked, index);
                    return index + 1;
                }
                braceDepth--;
                index++;
                continue;
            }
            if (templateExpression && current == '{') {
                braceDepth++;
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                index = maskQuotedString(source, masked, index, current);
                continue;
            }
            if (current == '`') {
                index = maskTemplateLiteral(source, masked, index);
                continue;
            }
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index = maskLineComment(source, masked, index);
                    continue;
                }
                if (next == '*') {
                    index = maskBlockComment(source, masked, index);
                    continue;
                }
                if (looksLikeRegexStart(masked, index)) {
                    index = maskRegexLiteral(source, masked, index);
                    continue;
                }
            }
            index++;
        }
        return index;
    }

    private static int maskQuotedString(
            String source,
            char[] masked,
            int index,
            char quote) {
        int openingQuote = index;
        mask(masked, index++);
        while (index < source.length()) {
            char current = source.charAt(index);
            mask(masked, index);
            if (current == '\\') {
                index++;
                if (index < source.length()) {
                    mask(masked, index);
                    index++;
                }
                continue;
            }
            index++;
            if (current == quote) {
                markValue(masked, index - 1);
                return index;
            }
        }
        markValue(masked, openingQuote);
        return index;
    }

    private static int maskTemplateLiteral(
            String source,
            char[] masked,
            int index) {
        int openingBacktick = index;
        mask(masked, index++);
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                mask(masked, index++);
                if (index < source.length()) {
                    mask(masked, index++);
                }
                continue;
            }
            if (current == '`') {
                mask(masked, index);
                markValue(masked, index);
                return index + 1;
            }
            if (current == '$'
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == '{') {
                mask(masked, index);
                mask(masked, index + 1);
                index = scanCode(source, masked, index + 2, true);
                continue;
            }
            mask(masked, index++);
        }
        markValue(masked, openingBacktick);
        return index;
    }

    private static int maskLineComment(
            String source,
            char[] masked,
            int index) {
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
            mask(masked, index++);
        }
        return index;
    }

    private static int maskBlockComment(
            String source,
            char[] masked,
            int index) {
        mask(masked, index++);
        if (index < source.length()) {
            mask(masked, index++);
        }
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '*'
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == '/') {
                mask(masked, index);
                mask(masked, index + 1);
                return index + 2;
            }
            mask(masked, index++);
        }
        return index;
    }

    private static boolean looksLikeRegexStart(char[] masked, int slashIndex) {
        int previous = previousSignificant(masked, slashIndex - 1);
        if (previous < 0) {
            return true;
        }
        char character = masked[previous];
        if ((character == '+' || character == '-')
                && previous > 0
                && masked[previous - 1] == character) {
            return false;
        }
        if ("([{:;,=!?&|+-*%^~<>".indexOf(character) >= 0) {
            return true;
        }
        if (!isIdentifierPart(character)) {
            return false;
        }
        int start = previous;
        while (start > 0 && isIdentifierPart(masked[start - 1])) {
            start--;
        }
        return REGEX_PREFIX_KEYWORDS.contains(
                new String(masked, start, previous - start + 1));
    }

    private static int maskRegexLiteral(
            String source,
            char[] masked,
            int index) {
        boolean characterClass = false;
        boolean escaped = false;
        mask(masked, index++);
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
            mask(masked, index);
            if (escaped) {
                escaped = false;
                index++;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                index++;
                continue;
            }
            if (current == '[') {
                characterClass = true;
                index++;
                continue;
            }
            if (current == ']') {
                characterClass = false;
                index++;
                continue;
            }
            index++;
            if (current == '/' && !characterClass) {
                while (index < source.length()
                        && Character.isLetter(source.charAt(index))) {
                    mask(masked, index++);
                }
                markValue(masked, index - 1);
                return index;
            }
        }
        return index;
    }

    private static boolean beginsWithDirectApiLiteral(
            String source,
            int index) {
        index = skipTrivia(source, index);
        if (index >= source.length()) {
            return false;
        }
        char quote = source.charAt(index);
        if (quote != '\'' && quote != '"' && quote != '`') {
            return false;
        }
        return source.startsWith("/api/", index + 1);
    }

    private static int skipTrivia(String source, int index) {
        while (index < source.length()) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '/'
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < source.length()
                        && source.charAt(index) != '\n'
                        && source.charAt(index) != '\r') {
                    index++;
                }
                continue;
            }
            if (current == '/'
                    && index + 1 < source.length()
                    && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                continue;
            }
            return index;
        }
        return index;
    }

    private static boolean isNamedFunctionDeclaration(
            String executable,
            int identifierStart) {
        int previous = previousSignificant(executable, identifierStart - 1);
        if (previous >= 0 && executable.charAt(previous) == '*') {
            previous = previousSignificant(executable, previous - 1);
        }
        if (previous < 0 || !isIdentifierPart(executable.charAt(previous))) {
            return false;
        }
        int start = previous;
        while (start > 0 && isIdentifierPart(executable.charAt(start - 1))) {
            start--;
        }
        return "function".equals(executable.substring(start, previous + 1));
    }

    private static int previousSignificant(char[] value, int index) {
        while (index >= 0 && Character.isWhitespace(value[index])) {
            index--;
        }
        return index;
    }

    private static int previousSignificant(String value, int index) {
        while (index >= 0 && Character.isWhitespace(value.charAt(index))) {
            index--;
        }
        return index;
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_'
                || character == '$';
    }

    private static int lineNumber(String source, int index) {
        int line = 1;
        for (int cursor = 0; cursor < index; cursor++) {
            if (source.charAt(cursor) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void mask(char[] value, int index) {
        if (value[index] != '\n' && value[index] != '\r') {
            value[index] = ' ';
        }
    }

    private static void markValue(char[] value, int index) {
        if (index >= 0
                && index < value.length
                && value[index] != '\n'
                && value[index] != '\r') {
            value[index] = VALUE_SENTINEL;
        }
    }

    record FetchCall(int line, boolean directApi) {
    }
}
