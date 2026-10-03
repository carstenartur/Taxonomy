package com.taxonomy.analysis.dag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity of the requirement version an operation analyses.
 *
 * <p>Messages carry this reference instead of the requirement text or prompt.
 * Workers load the authoritative requirement for the exact recorded scope and
 * verify it against {@link #textSha256()} before executing.</p>
 *
 * @param projectId     owning project for project analyses, otherwise {@code null}
 * @param requirementId persisted requirement, otherwise {@code null}
 * @param snapshotId    immutable snapshot identity, otherwise {@code null}
 * @param textSha256    lower-case hex SHA-256 of the exact UTF-8 requirement text
 */
public record RequirementReference(Long projectId, Long requirementId, String snapshotId, String textSha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public RequirementReference {
        Objects.requireNonNull(textSha256, "textSha256");
        if (!SHA256.matcher(textSha256).matches()) {
            throw new IllegalArgumentException("textSha256 must be a lower-case SHA-256 hex digest");
        }
        if (snapshotId != null && (snapshotId.isBlank() || snapshotId.length() > 256)) {
            throw new IllegalArgumentException("snapshotId must be non-blank and at most 256 characters");
        }
    }

    /** Ad-hoc requirement without persisted identity. */
    public static RequirementReference adHoc(String requirementText) {
        return new RequirementReference(null, null, null, sha256(requirementText));
    }

    public static RequirementReference of(Long projectId, Long requirementId, String snapshotId,
                                          String requirementText) {
        return new RequirementReference(projectId, requirementId, snapshotId, sha256(requirementText));
    }

    /** True when {@code requirementText} is exactly the referenced requirement version. */
    public boolean matches(String requirementText) {
        return requirementText != null && textSha256.equals(sha256(requirementText));
    }

    public static String sha256(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
