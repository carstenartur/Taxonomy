package com.taxonomy.editor;

import org.springframework.util.MultiValueMap;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Compatibility links retain decoded query coordinates inside the application's fixed shell. */
public final class ArchitectureWorkspaceRedirect {
    private ArchitectureWorkspaceRedirect() { }

    public static String working(MultiValueMap<String,String> coordinates) { return target("working",coordinates); }
    public static String snapshot(MultiValueMap<String,String> coordinates) { return target("snapshot",coordinates); }

    private static String target(String source, MultiValueMap<String,String> coordinates) {
        var target = new StringBuilder("redirect:/?architectureSource=").append(source);
        coordinates.forEach((name,values) -> {
            if ("architectureSource".equals(name)) return;
            if (values == null || values.isEmpty()) target.append('&').append(encode(name));
            else for (String value : values) {
                target.append('&').append(encode(name));
                if (value != null) target.append('=').append(encode(value));
            }
        });
        return target.append("#architecture").toString();
    }

    // Encode decoded parameter data, including literal '+' and reserved delimiters, exactly once.
    // The fixed redirect:/ prefix remains context-relative under Spring's RedirectView.
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
}
