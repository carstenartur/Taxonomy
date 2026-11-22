package com.taxonomy.plugins.sparx;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

final class QeaIds {
    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private QeaIds() { }

    static String key(String value) {
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return upper.length() == 36 ? "{" + upper + "}" : upper;
    }

    static boolean original(String key) {
        return key.length() == 38 && key.charAt(0) == '{' && key.charAt(37) == '}'
                && GUID.matcher(key.substring(1, 37)).matches()
                && !"{00000000-0000-0000-0000-000000000000}".equals(key);
    }

    static String guid(String value) { return guid(value, "QEA_IDENTITY_REQUIRED"); }
    static String guid(String value, String code) {
        String bare = value == null ? "" : value.trim();
        if (bare.startsWith("{") && bare.endsWith("}")) bare = bare.substring(1, bare.length() - 1);
        if (!GUID.matcher(bare).matches()) throw fail(code, "A valid original EA GUID is required; names and row IDs cannot replace it");
        return "{" + UUID.fromString(bare).toString().toUpperCase(Locale.ROOT) + "}";
    }
}
