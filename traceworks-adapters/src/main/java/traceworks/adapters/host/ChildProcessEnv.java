package traceworks.adapters.host;

import traceworks.core.compat.EarlierNames;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Environment hygiene for processes that run workflow-controlled content on the host: shell commands and, later, the
 * AI CLIs.
 *
 * <p>A {@link ProcessBuilder} starts with a copy of the parent's own environment. When the parent was started with
 * secrets as environment variables, each child would inherit them, and a prompt-injected agent in an unattended run
 * could use them; children also pass them on to whatever they start. The {@link HostCommandRunner} therefore passes
 * only an allowlist of operator variables; this scrub is the second line, and the only one for a process that needs
 * more of the environment (an AI CLI and its credentials). The secrets removed here are those of a workflow service
 * Traceworks may be started from ({@link EarlierNames#SERVICE_ENVIRONMENT}): the instance API key and keystore
 * password of the service, and the run token, API address and run label one of its runs hands its processes, which
 * belong to that other run. Credentials the AI CLIs need for their own provider ({@code CLAUDE_CODE_OAUTH_TOKEN},
 * {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}, ...) are not touched.
 *
 * <p>Traceworks sets no run token and no run label of its own, and this class starts no sandbox; containers come with
 * the container runtime adapter.
 */
public final class ChildProcessEnv {

    /** Environment variables no child process gets from its parent. */
    static final Set<String> SCRUBBED = Set.copyOf(EarlierNames.SERVICE_ENVIRONMENT);

    /**
     * The variables of the inherited environment a host command and a container runtime client get, besides the
     * {@linkplain #LOCALE_CATEGORIES locale categories}: what a shell needs to find its tools and to behave as the
     * operator's shell does, and nothing that grants access.
     */
    public static final Set<String> OPERATOR_ENVIRONMENT = Set.of("PATH", "HOME", "LANG", "TERM", "TMPDIR", "USER",
            "LOGNAME", "SHELL", "TZ");

    /**
     * The locale categories of the inherited environment a host command and a container runtime client get. Named
     * one by one, not by the prefix {@code LC_}: SSH's {@code AcceptEnv LC_*} would otherwise let a token travel in
     * as {@code LC_FOO}.
     */
    public static final Set<String> LOCALE_CATEGORIES = Set.of("LC_ALL", "LC_CTYPE", "LC_MESSAGES", "LC_TIME",
            "LC_NUMERIC", "LC_COLLATE", "LC_MONETARY", "LC_PAPER", "LC_NAME", "LC_ADDRESS", "LC_TELEPHONE",
            "LC_MEASUREMENT", "LC_IDENTIFICATION");

    private ChildProcessEnv() {
    }

    /**
     * The allowlist: the {@linkplain #OPERATOR_ENVIRONMENT operator variables} and the
     * {@linkplain #LOCALE_CATEGORIES locale categories} of an environment, nothing else.
     */
    public static Map<String, String> operatorEnvironment(Map<String, String> environment) {
        Map<String, String> kept = new HashMap<>();
        environment.forEach((name, value) -> {
            if (OPERATOR_ENVIRONMENT.contains(name) || LOCALE_CATEGORIES.contains(name)) {
                kept.put(name, value);
            }
        });
        return Map.copyOf(kept);
    }

    /**
     * A process builder for a host process: stderr merged into stdout (one stream, in the order the process wrote
     * it), the working directory set when given, the JVM's environment {@linkplain #scrub scrubbed}.
     */
    public static ProcessBuilder builder(List<String> command, String workingDirectory) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }
        scrub(pb);
        return pb;
    }

    /**
     * Like {@link #builder(List, String)}, but the process starts from {@code inheritedEnvironment} instead of the
     * JVM's environment, scrubbed the same way.
     */
    public static ProcessBuilder builder(List<String> command, String workingDirectory,
                                         Map<String, String> inheritedEnvironment) {
        ProcessBuilder pb = builder(command, workingDirectory);
        Map<String, String> environment = pb.environment();
        environment.clear();
        environment.putAll(inheritedEnvironment);
        scrub(environment);
        return pb;
    }

    /** Removes the {@linkplain #SCRUBBED scrubbed} variables from the environment the process will start with. */
    public static void scrub(ProcessBuilder pb) {
        scrub(pb.environment());
    }

    /** Removes the {@linkplain #SCRUBBED scrubbed} variables from {@code environment}. */
    public static void scrub(Map<String, String> environment) {
        SCRUBBED.forEach(environment::remove);
    }
}
