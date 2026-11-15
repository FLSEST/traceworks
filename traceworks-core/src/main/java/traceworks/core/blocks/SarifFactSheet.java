package traceworks.core.blocks;

import traceworks.core.port.CommandRunner;
import traceworks.core.util.SecretReplacer;
import traceworks.core.util.UntrustedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fact sheet from the SARIF files a tool container wrote: counts per severity as {@code FACT <prefix>_...} lines,
 * one {@code HIT <prefix>:} line per result and {@code NOT MEASURED} instead of a silent zero when nothing readable
 * is there. Tool texts are foreign: each has the run's secret values replaced and then goes through
 * {@link UntrustedText}, in that order, since the cleaning could change a value; and code snippets
 * ({@code region.snippet}, {@code contextRegion}) are never printed, so a secret a scanner found does not end up in
 * the step output. Symlinks in the output directory are ignored, never followed.
 */
final class SarifFactSheet {

    static final int MAX_HITS = 200;
    static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final int RAW_TAIL_LINES = 40;
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    /** Tool output that carries a SARIF/JSON report instead of a log ({@code "runs"}, {@code "snippet"}, {@code "$schema"}). */
    private static final Pattern SARIF_LIKE = Pattern.compile("\"(?:runs|snippet|\\$schema)\"\\s*:");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The sheet, whether it rests on readable SARIF, and the number of unsuppressed results. */
    record Result(String text, boolean measured, int findings) {}

    private record Hit(String severity, String location, String ruleId, String message) {}

    private SarifFactSheet() {}

    /** {@link #read(Path, String, String, int, String, String)} for a tool that sees the project at /workspace. */
    static Result read(Path hostDir, String containerDir, String prefix, int exitCode, String rawOutput) {
        return read(hostDir, containerDir, prefix, exitCode, rawOutput, SarifLocation.WORKSPACE);
    }

    /** @param workspace where the tool's container sees the project; its locations are made relative to it */
    static Result read(Path hostDir, String containerDir, String prefix, int exitCode, String rawOutput,
                       String workspace) {
        return read(hostDir, containerDir, prefix, exitCode, rawOutput, workspace, SecretReplacer.none(),
                CommandRunner.sizeText(CommandRunner.OUTPUT_LIMIT_BYTES));
    }

    /**
     * @param workspace   where the tool's container sees the project; its locations are made relative to it
     * @param secrets     replaces the run's secret values, in every text of the tool before that text is cleaned
     * @param outputLimit the output limit the tool ran with, such as {@code 16 MiB}, which a stop at it names
     */
    static Result read(Path hostDir, String containerDir, String prefix, int exitCode, String rawOutput,
                       String workspace, SecretReplacer secrets, String outputLimit) {
        String aborted = abortReason(exitCode, outputLimit);
        if (aborted != null) return notMeasured(secrets, prefix, aborted, exitCode, rawOutput);
        List<Path> files;
        try {
            files = sarifFiles(hostDir);
        } catch (IOException e) {
            return notMeasured(secrets, prefix,
                    "Output directory not readable: " + e.getMessage(), exitCode, rawOutput);
        }
        if (files.isEmpty()) {
            return notMeasured(secrets, prefix, "no SARIF file in " + containerDir
                    + " - does the tool write its report there?", exitCode, rawOutput);
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> tools = new LinkedHashSet<>();
        int suppressed = 0;
        long bytes = 0;
        for (Path file : files) {
            try {
                bytes += Files.size(file);
                if (bytes > MAX_TOTAL_BYTES) {
                    return notMeasured(secrets, prefix,
                            "SARIF files larger than 50 MB together", exitCode, rawOutput);
                }
                JsonNode runs = MAPPER.readTree(file.toFile()).path("runs");
                if (!runs.isArray()) {
                    return notMeasured(secrets, prefix,
                            file.getFileName() + " is not SARIF (runs missing)", exitCode, rawOutput);
                }
                if (runs.isEmpty()) {
                    return notMeasured(secrets, prefix, file.getFileName() + " contains no run", exitCode, rawOutput);
                }
                for (JsonNode run : runs) {
                    if (failedInvocation(run)) {
                        return notMeasured(secrets, prefix,
                                "Tool reports executionSuccessful=false", exitCode, rawOutput);
                    }
                    // SARIF: results missing or null = no analysis; [] = analysed, nothing found
                    if (!run.path("results").isArray()) {
                        return notMeasured(secrets, prefix,
                                file.getFileName() + ": a run without results (no analysis)",
                                exitCode, rawOutput);
                    }
                    tools.add(toolLabel(run));
                    suppressed += collect(run, hits, workspace, secrets);
                }
            } catch (IOException e) {
                return notMeasured(secrets, prefix,
                        file.getFileName() + " not readable: " + e.getMessage(), exitCode, rawOutput);
            }
        }
        return new Result(sheet(secrets, prefix, tools, files.size(), hits, suppressed, exitCode), true, hits.size());
    }

    /** Regular files named *.sarif or *.sarif.json directly in {@code dir}; symlinks are skipped. */
    private static List<Path> sarifFiles(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sarif") || name.endsWith(".sarif.json");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static boolean failedInvocation(JsonNode run) {
        for (JsonNode invocation : run.path("invocations")) {
            JsonNode ok = invocation.path("executionSuccessful");
            if (ok.isBoolean() && !ok.asBoolean()) return true;
        }
        return false;
    }

    private static String toolLabel(JsonNode run) {
        JsonNode driver = run.path("tool").path("driver");
        String version = driver.path("version").asText(driver.path("semanticVersion").asText(""));
        return (driver.path("name").asText("?") + " " + version).trim();
    }

    /** Adds one hit per unsuppressed result of {@code run}; returns the number of suppressed ones. */
    private static int collect(JsonNode run, List<Hit> hits, String workspace, SecretReplacer secrets) {
        JsonNode rules = run.path("tool").path("driver").path("rules");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode rule : rules) byId.putIfAbsent(rule.path("id").asText(""), rule);
        int suppressed = 0;
        for (JsonNode result : run.path("results")) {
            if (result.path("suppressions").size() > 0) {
                suppressed++;
                continue;
            }
            String ruleId = ruleId(result);
            JsonNode rule = rule(result, rules, byId.get(ruleId));
            String message = result.path("message").path("text").asText("");
            if (message.isBlank()) message = rule.path("shortDescription").path("text").asText("");
            String location = SarifLocation.of(result, run.path("originalUriBaseIds"), workspace, secrets);
            hits.add(new Hit(severity(result, rule), location, ruleId, message));
        }
        return suppressed;
    }

    private static String ruleId(JsonNode result) {
        String id = result.path("ruleId").asText("");
        return id.isEmpty() ? result.path("rule").path("id").asText("") : id;
    }

    private static JsonNode rule(JsonNode result, JsonNode rules, JsonNode byId) {
        int index = result.path("ruleIndex").asInt(-1);
        if (index >= 0 && index < rules.size()) return rules.get(index);
        return byId == null ? MissingNode.getInstance() : byId;
    }

    /** {@code security-severity} (result, then rule) on the CVSS scale; otherwise the SARIF level. */
    static String severity(JsonNode result, JsonNode rule) {
        Double score = score(result.path("properties").path("security-severity"));
        if (score == null) score = score(rule.path("properties").path("security-severity"));
        if (score != null) {
            return score >= 9.0 ? "CRITICAL" : score >= 7.0 ? "HIGH" : score >= 4.0 ? "MEDIUM" : "LOW";
        }
        String level = result.path("level").asText("");
        if (level.isEmpty()) level = rule.path("defaultConfiguration").path("level").asText("warning");
        return switch (level) {
            case "error" -> "HIGH";
            case "note", "none" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private static Double score(JsonNode node) {
        if (node.isNumber()) return node.asDouble();
        if (!node.isTextual()) return null;
        try {
            return Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Exit codes that mean the tool did not run to its end — timeout, cancel, killed (OOM), stopped for an output
     * beyond the limit, the container never started, ended by any other signal (128 + n: a segfault, an abort, a
     * SIGTERM from a daemon restart), or 255, a crash or an {@code exit(-1)}. Whatever SARIF lies in the directory then
     * is partial at best. The exits tools use for "findings found" (1, 2, or a small code the operator chooses) are no
     * abort.
     */
    private static String abortReason(int exitCode, String outputLimit) {
        return switch (exitCode) {
            case 124 -> "Timeout - tool stopped (exit 124)";
            case 125 -> "Container not started (exit 125: image not allowed or the container run failed)";
            case 126, 127 -> "Entrypoint not executable or not found (exit " + exitCode + ")";
            case 130 -> "Run cancelled (exit 130)";
            case 137 -> "Container killed (exit 137, for example by the memory limit)";
            case CommandRunner.OUTPUT_LIMIT_EXIT_CODE -> "Output beyond " + outputLimit + " - tool stopped (exit 153)";
            case 255 -> "Tool failed (exit 255)";
            default -> exitCode > 128 && exitCode < 160
                    ? "Tool ended by signal " + (exitCode - 128) + " (exit " + exitCode + ")" : null;
        };
    }

    private static String sheet(SecretReplacer secrets, String prefix, Set<String> tools,
                                int fileCount, List<Hit> hits, int suppressed, int exitCode) {
        Map<String, Integer> counts = new HashMap<>();
        hits.forEach(hit -> counts.merge(hit.severity(), 1, Integer::sum));
        StringBuilder out = new StringBuilder("## FACT SHEET ").append(prefix).append(" (")
                .append(inline(String.join(", ", tools), 120, secrets)).append(", ").append(fileCount)
                .append(fileCount == 1 ? " SARIF file)" : " SARIF files)").append('\n');
        fact(out, prefix + "_findings", hits.size());
        for (String severity : SEVERITIES) {
            fact(out, prefix + "_" + severity.toLowerCase(Locale.ROOT), counts.getOrDefault(severity, 0));
        }
        fact(out, prefix + "_suppressed", suppressed);
        fact(out, prefix + "_exit_code", exitCode);
        hits.stream()
                .sorted(Comparator.comparingInt((Hit hit) -> SEVERITIES.indexOf(hit.severity()))
                        .thenComparing(Hit::location))
                .limit(MAX_HITS)
                .forEach(hit -> out.append("HIT ").append(prefix).append(": ")
                        .append(inline(hit.location(), 300, secrets)).append(' ')
                        .append(hit.severity()).append(' ')
                        .append(hit.ruleId().isBlank() ? "-" : inline(hit.ruleId(), 120, secrets))
                        .append(" – ").append(inline(hit.message(), UntrustedText.MAX_TITLE_CHARS, secrets))
                        .append('\n'));
        if (hits.size() > MAX_HITS) {
            out.append("HIT ").append(prefix).append(": (+").append(hits.size() - MAX_HITS)
                    .append(" more, not listed one by one)\n");
        }
        return out.toString().stripTrailing();
    }

    /**
     * One text of the tool as one cleaned line. The run's secret values are replaced first: the cleaning rewrites
     * text (a marker loses its colon, a block boundary its brackets and its case, a line with a disguised marker is
     * normalised), and a value it changed, on its own or through the text around it, is no exact match for the
     * record's redactor any more. The cleaning leaves a replacement as it is, and the cut keeps each one whole.
     */
    private static String inline(String text, int maxChars, SecretReplacer secrets) {
        return UntrustedText.inline(secrets.replace(text), maxChars, secrets.replacements());
    }

    private static void fact(StringBuilder out, String name, int value) {
        out.append("FACT ").append(name).append(": ").append(value).append('\n');
    }

    private static Result notMeasured(SecretReplacer secrets, String prefix, String reason,
                                      int exitCode, String rawOutput) {
        StringBuilder out = new StringBuilder("## FACT SHEET ").append(prefix).append('\n')
                .append("NOT MEASURED: ").append(prefix).append("_findings (")
                .append(inline(reason, 300, secrets)).append(")\n")
                .append("FACT ").append(prefix).append("_exit_code: ").append(exitCode).append('\n');
        if (rawOutput != null && SARIF_LIKE.matcher(rawOutput).find()) {
            // a report on stdout would bring its snippets (secrets) into the step output
            out.append("## TOOL LOG: contains SARIF/JSON data and is not shown - write the report to the output "
                    + "directory\n");
        } else if (rawOutput != null && !rawOutput.isBlank()) {
            out.append("## TOOL LOG (last ").append(RAW_TAIL_LINES).append(" lines)\n");
            // The values go first: the defusing changes text, and a changed value is one the record cannot find
            List<String> lines = UntrustedText.defuseLines(secrets.replace(rawOutput)).lines().toList();
            // "| " before every line: a log line "FACT x: 0" must never count as a measured fact
            lines.subList(Math.max(0, lines.size() - RAW_TAIL_LINES), lines.size())
                    .forEach(line -> out.append("| ").append(line).append('\n'));
        }
        return new Result(out.toString().stripTrailing(), false, 0);
    }
}
