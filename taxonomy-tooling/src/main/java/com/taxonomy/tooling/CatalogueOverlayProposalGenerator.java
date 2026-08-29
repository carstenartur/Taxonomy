package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.FanOut;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.OverlayModel;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Patch;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Proposal;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SemanticChange;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceCatalogue;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Summary;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates a deterministic, review-only catalogue-overlay proposal.
 *
 * <p>The checked-in overlay is an input and can never be the output. Reviewed
 * mappings remain authoritative; provisional and newly discovered strict-scope
 * nodes receive deterministic suggestions and a semantic diff for human review.
 * No LLM, timestamp, network access or mutable database state is used.</p>
 */
public final class CatalogueOverlayProposalGenerator {

    static final String ALGORITHM_VERSION = "catalogue-overlay-proposal-v1";
    static final int OUTPUT_SCHEMA_VERSION = 1;
    static final String ROLE_PRODUCT = "PRODUCT";
    static final String ROLE_PRODUCT_FAMILY = "PRODUCT_FAMILY";

    private static final String DEFAULT_CATALOGUE =
            "taxonomy-app/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx";
    private static final String DEFAULT_OVERLAY =
            "taxonomy-app/src/main/resources/data/nato-taxonomy.json";
    private static final String DEFAULT_OUTPUT =
            "target/catalogue-overlay/catalogue-overlay-proposal.json";
    private static final String DEFAULT_REPORT =
            "target/catalogue-overlay/catalogue-overlay-review.md";

    private CatalogueOverlayProposalGenerator() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments, Path.of("."), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(
            String[] arguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            if (arguments.length == 1 && "--help".equals(arguments[0])) {
                usage(output);
                return 0;
            }
            Map<String, String> options = parseOptions(arguments);
            Path root = resolve(workingDirectory, options.getOrDefault("root", "."));
            Path catalogue = resolve(root, options.getOrDefault("catalogue", DEFAULT_CATALOGUE));
            Path overlay = resolve(root, options.getOrDefault("overlay", DEFAULT_OVERLAY));
            Path proposal = resolve(root, options.getOrDefault("output", DEFAULT_OUTPUT));
            Path report = resolve(root, options.getOrDefault("report", DEFAULT_REPORT));

            Result result = generate(catalogue, overlay, proposal, report);
            output.println("Catalogue overlay proposal generated:");
            output.println("  Proposal: " + result.proposalOutput());
            output.println("  Review report: " + result.reviewReport());
            output.println("  Strict-scope nodes: " + result.strictNodeCount());
            output.println("  Semantic changes: " + result.semanticChangeCount());
            output.println("  Unresolved proposals: " + result.unresolvedCount());
            output.println("  Proposal SHA-256: " + result.proposalSha256());
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("Catalogue overlay proposal generation failed: "
                    + failure.getMessage());
            return 1;
        }
    }

    static Result generate(
            Path catalogue,
            Path reviewedOverlay,
            Path proposalOutput,
            Path reviewReport) throws IOException {
        Path cataloguePath = normalizeExisting(catalogue, "catalogue workbook");
        Path overlayPath = normalizeExisting(reviewedOverlay, "reviewed overlay");
        Path proposalPath = proposalOutput.toAbsolutePath().normalize();
        Path reportPath = reviewReport.toAbsolutePath().normalize();
        requireDistinctOutputs(cataloguePath, overlayPath, proposalPath, reportPath);

        String catalogueSha = sha256(cataloguePath);
        String overlaySha = sha256(overlayPath);
        OverlayModel overlay = CatalogueOverlayProposalInputs.readOverlay(
                overlayPath, cataloguePath.getFileName().toString());
        SourceCatalogue source = CatalogueOverlayProposalInputs.readSourceCatalogue(
                cataloguePath, overlay.strictRoot());
        CatalogueOverlayProposalInputs.validateOverlayAgainstSource(overlay, source);

        Map<String, String> currentParents =
                CatalogueOverlayProposalInputs.effectiveParents(source, overlay);
        CatalogueOverlayProposalValidator.validateHierarchy(
                currentParents, source.nodes(), overlay.strictRoot(), "reviewed overlay");
        CatalogueOverlayProposalValidator.validateOverlayProductLeaves(
                overlay.patches(), currentParents, "reviewed overlay");

        Set<String> candidateFamilies =
                CatalogueOverlayProposalInputs.candidateFamilyCodes(overlay, source);
        List<Proposal> proposals = CatalogueOverlayProposalPlanner.buildProposals(
                overlay, source, currentParents, candidateFamilies);

        Map<String, String> proposedParents = new LinkedHashMap<>(currentParents);
        Map<String, String> roles = overlay.patches().values().stream()
                .collect(Collectors.toMap(
                        Patch::code,
                        Patch::analysisRole,
                        (left, right) -> left,
                        LinkedHashMap::new));
        for (Proposal proposal : proposals) {
            roles.put(proposal.code(), proposal.analysisRole());
            if (proposal.proposedParentCode() != null) {
                proposedParents.put(proposal.code(), proposal.proposedParentCode());
            }
        }
        CatalogueOverlayProposalValidator.validateHierarchy(
                proposedParents, source.nodes(), overlay.strictRoot(), "proposal");
        CatalogueOverlayProposalValidator.validateProductLeaves(
                roles, proposedParents, "proposal");

        List<SemanticChange> changes =
                CatalogueOverlayProposalPlanner.semanticChanges(proposals);
        FanOut currentFanOut = CatalogueOverlayProposalPlanner.fanOut(currentParents);
        FanOut proposedFanOut = CatalogueOverlayProposalPlanner.fanOut(proposedParents);
        Summary summary = summarize(proposals, changes, candidateFamilies,
                currentFanOut, proposedFanOut);

        Map<String, Object> document = CatalogueOverlayProposalRenderer.proposalDocument(
                cataloguePath,
                overlayPath,
                catalogueSha,
                overlaySha,
                overlay,
                summary,
                currentFanOut,
                proposedFanOut,
                changes,
                proposals);
        String proposalText = FlatJson.pretty(document) + "\n";
        String reportText = CatalogueOverlayProposalRenderer.reviewReport(
                cataloguePath,
                overlayPath,
                catalogueSha,
                overlaySha,
                overlay,
                summary,
                currentFanOut,
                proposedFanOut,
                changes,
                proposals);

        atomicWrite(proposalPath, proposalText);
        atomicWrite(reportPath, reportText);
        return new Result(
                proposalPath,
                reportPath,
                summary.strictNodeCount(),
                summary.semanticChangeCount(),
                summary.unresolvedCount(),
                sha256(proposalText.getBytes(StandardCharsets.UTF_8)));
    }

    private static Summary summarize(
            List<Proposal> proposals,
            List<SemanticChange> changes,
            Set<String> candidateFamilies,
            FanOut currentFanOut,
            FanOut proposedFanOut) {
        long unresolved = proposals.stream().filter(Proposal::unresolved).count();
        long reviewedLocked = proposals.stream()
                .filter(proposal -> "REVIEWED_LOCKED".equals(proposal.status()))
                .count();
        long newMappings = proposals.stream()
                .filter(proposal -> proposal.status().startsWith("NEW_"))
                .count();
        return new Summary(
                proposals.size(),
                reviewedLocked,
                proposals.size() - reviewedLocked,
                newMappings,
                unresolved,
                changes.size(),
                candidateFamilies.size(),
                currentFanOut.maximum(),
                proposedFanOut.maximum());
    }

    private static Path normalizeExisting(Path path, String description) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(
                    "Configured " + description + " does not exist: " + normalized);
        }
        return normalized;
    }

    private static void requireDistinctOutputs(
            Path catalogue,
            Path overlay,
            Path proposal,
            Path report) throws IOException {
        if (sameFile(catalogue, proposal) || sameFile(catalogue, report)) {
            throw new IllegalArgumentException(
                    "The catalogue workbook is read-only input and cannot be an output path");
        }
        if (sameFile(overlay, proposal) || sameFile(overlay, report)) {
            throw new IllegalArgumentException(
                    "The reviewed overlay is read-only input and cannot be an output path");
        }
        if (sameFile(proposal, report)) {
            throw new IllegalArgumentException(
                    "Proposal JSON and review report must use different output paths");
        }
    }

    private static boolean sameFile(Path left, Path right) throws IOException {
        if (left.equals(right)) {
            return true;
        }
        return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".catalogue-overlay-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<String, String> parseOptions(String[] arguments) {
        Set<String> allowed = Set.of("root", "catalogue", "overlay", "output", "report");
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            String token = arguments[index];
            if (!token.startsWith("--") || token.length() == 2) {
                throw new IllegalArgumentException("Expected --option, got " + token);
            }
            String name = token.substring(2);
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name);
            }
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + name);
            }
            if (options.putIfAbsent(name, arguments[++index]) != null) {
                throw new IllegalArgumentException("Duplicate option --" + name);
            }
        }
        return options;
    }

    private static void usage(PrintStream output) {
        output.println("Usage: CatalogueOverlayProposalGenerator [options]");
        output.println("  --root <directory>");
        output.println("  --catalogue <xlsx>");
        output.println("  --overlay <reviewed-json>");
        output.println("  --output <proposal-json>");
        output.println("  --report <review-markdown>");
    }

    private static Path resolve(Path base, String value) {
        Path candidate = Path.of(value);
        return (candidate.isAbsolute() ? candidate : base.resolve(candidate))
                .toAbsolutePath().normalize();
    }

    record Result(
            Path proposalOutput,
            Path reviewReport,
            long strictNodeCount,
            long semanticChangeCount,
            long unresolvedCount,
            String proposalSha256) {
    }
}
