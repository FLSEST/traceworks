package traceworks.adapters.pkg;

import traceworks.core.port.CommandRunner;
import traceworks.core.port.Ids;
import traceworks.core.port.SourceCheckout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checkout of a source with git, below {@code <home>/workspaces/}: {@code git clone --no-checkout --no-hardlinks
 * [--local] --no-recurse-submodules}, then {@code git checkout --detach <sha>}.
 *
 * <p>Nothing of the source runs, and nothing of the operator's configuration that would run a program for it. Every
 * call is a {@link GitProcess} (no fsmonitor, no hooks, no inherited {@code GIT_*} variable) and also passes
 * {@code -c protocol.file.allow=user}, {@code -c protocol.ext.allow=never} and {@code -c submodule.recurse=false}; the
 * clone copies no init template, and asks for no password ({@code GIT_TERMINAL_PROMPT=0}: a credential comes from a
 * helper or an SSH key, or the clone fails). Before the checkout,
 * {@code .git/info/attributes}, which beats the source's {@code .gitattributes}, says {@value #NO_CONVERSION}: no
 * filter driver runs, not even one the operator's own configuration defines for an attribute the source names
 * (git-lfs: an LFS file stays its pointer), and the checkout holds the commit's bytes, with no {@code $Id$}
 * expansion, no line endings converted ({@code -text}, which also switches {@code eol} off) and no
 * {@code working-tree-encoding}. A source or a ref never becomes an option of git.
 *
 * <p>Allowed sources ({@link Source#parse}): an absolute local path, and the URLs {@code https://}, {@code ssh://},
 * {@code file://} and the scp-like {@code user@host:path}. A source is fetched with the operator's credentials, so a
 * transport without TLS ({@code http://}, {@code git://}) is refused, and so is one that runs a program
 * ({@code ext::}, any {@code <helper>::}).
 *
 * <p>A URL's credentials, query and fragment never leave the clone call: the {@link Checkout} names the URL without
 * them, the clone's {@code remote.origin.url} is set to that, and git's error output is passed on with them replaced.
 */
public final class GitSourceCheckout implements SourceCheckout {

    /** How long one git call may take: a clone of a large repository takes a while. */
    static final int TIMEOUT_SECONDS = 600;

    /** The attributes of every path in the checkout: no filter, no ident, no conversion. */
    static final String NO_CONVERSION = "* -filter -ident -text -working-tree-encoding";

    /** The options after {@link GitProcess#SAFE_OPTIONS} on every call. */
    private static final List<String> OPTIONS = List.of("-c", "protocol.file.allow=user",
            "-c", "protocol.ext.allow=never", "-c", "submodule.recurse=false");

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    /** How much of git's error output a message keeps, from its end. */
    private static final int ERROR_TAIL = 2000;

    private final Path root;
    /** The directories of the home that steps write: no local source is cloned from one of them. */
    private final List<CheckoutsDirectory> stepWritten;
    private final GitProcess git;

    /**
     * @param root where the checkouts lie, {@code <home>/workspaces}: no local source is cloned from there
     * @param runs where the runs' folders with their scratch directories lie, {@code <home>/runs}: nor from there
     */
    public GitSourceCheckout(Path root, Path runs) {
        this(root, runs, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /**
     * @param root        where the checkouts lie, {@code <home>/workspaces}: no local source is cloned from there
     * @param runs        where the runs' folders with their scratch directories lie, {@code <home>/runs}: nor from
     *                    there
     * @param outputLimit how many bytes of git's stdout and of its stderr one call keeps: the operator's
     *                    {@code output_limit_mib}; a call that goes beyond it is stopped and fails the checkout
     */
    public GitSourceCheckout(Path root, Path runs, int outputLimit) {
        this(root, runs, "git", TIMEOUT_SECONDS * 1000L, Map.of(), outputLimit);
    }

    /**
     * @param root        where the checkouts lie, {@code <home>/workspaces}: no local source is cloned from there
     * @param runs        every folder that holds run folders with their scratch directories: the runs folder of the
     *                    home, and {@code <home>/runs} when the operator named another one. No local source is
     *                    cloned from any of them
     * @param outputLimit how many bytes of git's stdout and of its stderr one call keeps
     */
    public GitSourceCheckout(Path root, List<Path> runs, int outputLimit) {
        this(root, CheckoutsDirectory.stepWritten(root, runs), "git", TIMEOUT_SECONDS * 1000L, Map.of(), outputLimit);
    }

    /**
     * For tests: without the runs' folders, with another git executable, timeout and extra variables of the parent
     * environment.
     */
    GitSourceCheckout(Path root, String gitExecutable, long timeoutMillis, Map<String, String> parentEnvironment) {
        this(root, (Path) null, gitExecutable, timeoutMillis, parentEnvironment, CommandRunner.OUTPUT_LIMIT_BYTES);
    }

    /** For tests: without the runs' folders, with another output limit as well. */
    GitSourceCheckout(Path root, String gitExecutable, long timeoutMillis, Map<String, String> parentEnvironment,
                      int outputLimit) {
        this(root, (Path) null, gitExecutable, timeoutMillis, parentEnvironment, outputLimit);
    }

    /** @param runs the runs' folders; {@code null} for none, which only a test leaves out */
    GitSourceCheckout(Path root, Path runs, String gitExecutable, long timeoutMillis,
                      Map<String, String> parentEnvironment, int outputLimit) {
        this(root, CheckoutsDirectory.stepWritten(root, runs == null ? List.of() : List.of(runs)), gitExecutable,
                timeoutMillis, parentEnvironment, outputLimit);
    }

    private GitSourceCheckout(Path root, List<CheckoutsDirectory> stepWritten, String gitExecutable,
                              long timeoutMillis, Map<String, String> parentEnvironment, int outputLimit) {
        this.root = Objects.requireNonNull(root, "root");
        this.stepWritten = stepWritten;
        this.git = new GitProcess(gitExecutable, OPTIONS, parentEnvironment, Map.of("GIT_TERMINAL_PROMPT", "0"),
                timeoutMillis, outputLimit);
    }

    @Override
    public Path directoryOf(String runId) {
        if (!Ids.isRunId(runId)) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        return root.resolve(runId);
    }

    @Override
    public Checkout checkout(String source, String ref, Path target) {
        Source parsed = Source.parse(source);
        if (parsed.localPath() != null) {
            // A clone runs git-upload-pack in a local source, which reads the source's .git/config: never in a
            // directory that steps write
            for (CheckoutsDirectory kept : stepWritten) {
                if (kept.holds(Path.of(parsed.localPath()))) {
                    throw new IllegalArgumentException(kept.refusal("the source", Path.of(parsed.localPath())));
                }
            }
        }
        if (ref != null && (ref.isBlank() || hasControlCharacter(ref))) {
            throw new IllegalArgumentException("the ref '" + parsed.scrub(ref) + "' is blank or holds a control"
                    + " character");
        }
        if (exists(target)) {
            throw new IllegalArgumentException("the checkout " + target + " exists already; a checkout is never"
                    + " reused for another run");
        }
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("cannot create " + target.getParent() + ": " + e, e);
        }
        try {
            // --origin: the operator's clone.defaultRemoteName would give the remote another name, and the URL
            // as given, with its credentials, would then stay in .git/config under that name, where a step reads it
            // No reflog: its first line would be "clone: from <URL>", with the query and the fragment of the URL
            // as given, in a folder the steps read
            List<String> clone = new ArrayList<>(List.of("-c", "init.templateDir=", "-c",
                    "core.logAllRefUpdates=false", "clone", "--quiet",
                    "--origin", "origin", "--no-checkout", "--no-hardlinks", "--no-recurse-submodules"));
            if (parsed.local()) {
                clone.add("--local");
            }
            clone.addAll(List.of("--", parsed.given(), target.toString()));
            call(parsed, target.toAbsolutePath().getParent(), "cannot clone " + parsed.recorded(), clone);
            if (!parsed.recorded().equals(parsed.given())) {
                // A step reads .git/config: the credentials of the URL must not stay in it
                call(parsed, target, "cannot set the remote of " + target,
                        List.of("config", "--end-of-options", "remote.origin.url", parsed.recorded()));
            }
            Path info = Files.createDirectories(target.resolve(".git").resolve("info"));
            Files.writeString(info.resolve("attributes"), NO_CONVERSION + "\n", StandardCharsets.UTF_8);
            String sha = resolve(parsed, ref, target);
            call(parsed, target, "cannot check " + sha + " out of " + parsed.recorded(),
                    List.of("checkout", "--quiet", "--no-recurse-submodules", "--detach", sha, "--"));
            return new Checkout(parsed.recorded(), sha);
        } catch (IOException e) {
            removeQuietly(target);
            throw new IllegalStateException("cannot write into the checkout " + target + ": " + e, e);
        } catch (RuntimeException e) {
            removeQuietly(target);
            throw e;
        }
    }

    /**
     * The full commit {@code ref} names in the clone: as a revision first ({@code <ref>^{commit}}: a commit, a tag, the
     * default branch, which the clone has as a local branch), then as a branch of the source, which a clone without a
     * checkout has as {@code refs/remotes/origin/<ref>} only.
     */
    private String resolve(Source source, String ref, Path target) {
        List<String> candidates = ref == null ? List.of("HEAD") : List.of(ref, "refs/remotes/origin/" + ref);
        for (String candidate : candidates) {
            GitProcess.Result result = run(source, target, "cannot resolve " + source.scrub(candidate),
                    List.of("rev-parse", "--verify", "--quiet", "--end-of-options", candidate + "^{commit}"));
            String sha = result.outputText().strip();
            if (result.exitCode() == 0 && SHA.matcher(sha).matches()) {
                return sha;
            }
        }
        throw new IllegalArgumentException(ref == null
                ? "the source " + source.recorded() + " has no commit at its HEAD"
                : "the ref '" + source.scrub(ref) + "' names no commit of " + source.recorded());
    }

    /** A git call that must succeed; its failure names {@code what} and git's error output, scrubbed. */
    private void call(Source source, Path directory, String what, List<String> args) {
        GitProcess.Result result = run(source, directory, what, args);
        if (result.exitCode() != 0) {
            String error = result.errorText().strip();
            if (error.length() > ERROR_TAIL) {
                error = "..." + error.substring(error.length() - ERROR_TAIL);
            }
            throw new IllegalStateException(source.scrub("git " + what + " (exit code " + result.exitCode() + ")"
                    + (error.isEmpty() ? "" : ": " + error.replaceAll("\\s*\\R\\s*", " "))));
        }
    }

    /**
     * A git call; one that cannot run, does not finish in time or prints beyond the output limit is an {@link IllegalStateException} with a scrubbed
     * message and no cause, since a cause's message is git's or the JDK's and could repeat the source as given.
     */
    private GitProcess.Result run(Source source, Path directory, String what, List<String> args) {
        try {
            return git.run(directory, args);
        } catch (GitProcess.TimedOut e) {
            throw new IllegalStateException(source.scrub("git " + what + ": it did not finish within "
                    + git.timeoutMillis() / 1000 + " seconds"));
        } catch (GitProcess.OutputLimitExceeded e) {
            throw new IllegalStateException(source.scrub("git " + what + ": " + e.getMessage()
                    + " (output_limit_mib in traceworks.json)"));
        } catch (IOException e) {
            throw new IllegalStateException(source.scrub("git " + what + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(source.scrub("git " + what + ": interrupted"), e);
        }
    }

    @Override
    public boolean exists(Path target) {
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void remove(Path target) {
        if (!exists(target)) {
            return;
        }
        try {
            // Without FOLLOW_LINKS a link is visited as a file and removed itself, never what it points to
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e.toString(), e);
        }
    }

    private void removeQuietly(Path target) {
        try {
            remove(target);
        } catch (UncheckedIOException ignored) {
            // the failure that brought us here is the one to report
        }
    }

    private static boolean hasControlCharacter(String text) {
        return text.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /**
     * A source as git reads it and as the record names it.
     *
     * @param given    the source as given, which only the clone call sees
     * @param recorded the source the record names: a URL without its credentials, query and fragment (the user of an
     *                 {@code ssh://} URL stays: it is no credential, and a clone again needs it)
     * @param local    whether it is a local path, which the clone copies directly ({@code --local})
     * @param secrets  the parts of the given source that no message may show
     * @param localPath the repository on this machine that git reads: the path, or the decoded path of a
     *                  {@code file://} URL; {@code null} for a source elsewhere
     */
    record Source(String given, String recorded, boolean local, List<String> secrets, String localPath) {

        private static final Set<String> SCHEMES = Set.of("https", "ssh", "file");
        private static final Pattern URL = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://(.*)", Pattern.DOTALL);
        private static final Pattern HELPER = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*::.*", Pattern.DOTALL);
        /** {@code user@host:path}: the user before the first ':', a host without '/', ':' or '@'. */
        private static final Pattern SCP = Pattern.compile("([^@/:]+)@([^@/:]+):(.+)", Pattern.DOTALL);
        /** Shorter parts are not replaced on their own in a message: they would garble it, and the URL forms are. */
        private static final int MIN_SECRET = 4;

        /**
         * @throws IllegalArgumentException if the source is not an allowed one; the message names no credential
         */
        static Source parse(String source) {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("no source given");
            }
            if (hasControlCharacter(source)) {
                throw new IllegalArgumentException("a source with a control character is refused");
            }
            // A refusal does not repeat the source: it may hold a credential
            if (source.startsWith("-")) {
                throw new IllegalArgumentException("the source starts with '-', which git would read as an option");
            }
            Matcher url = URL.matcher(source);
            if (url.matches()) {
                return url(source, url.group(1), url.group(2));
            }
            if (HELPER.matcher(source).matches()) {
                throw new IllegalArgumentException("the source names a git remote helper ("
                        + source.substring(0, source.indexOf("::")) + "::), which runs a program; use a path or an"
                        + " https://, ssh:// or file:// URL");
            }
            if (SourceCheckout.isLocalPath(source)) {
                if (!source.startsWith("/")) {
                    throw new IllegalArgumentException("the source path is not absolute");
                }
                return new Source(source, source, true, List.of(), source);
            }
            Matcher scp = SCP.matcher(source);
            if (scp.matches() && !unbracketed(scp.group(2)).startsWith("-")) {
                return new Source(source, source, false, List.of(), null);
            }
            throw new IllegalArgumentException("the source is neither a local path nor an allowed URL (https://,"
                    + " ssh://, file:// or user@host:path)");
        }

        private static Source url(String source, String scheme, String rest) {
            if (!SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("the source scheme " + scheme + ":// is not allowed: a source is"
                        + " fetched with the operator's credentials; use https://, ssh:// or file://");
            }
            int end = rest.length();
            for (char stop : new char[] {'/', '?', '#'}) {
                int at = rest.indexOf(stop);
                if (at >= 0 && at < end) end = at;
            }
            String authority = rest.substring(0, end);
            int at = authority.lastIndexOf('@');
            String userinfo = at < 0 ? "" : authority.substring(0, at);
            String host = authority.substring(at + 1);
            String pathAndMore = rest.substring(end);
            int query = indexOfAny(pathAndMore, '?', '#');
            String path = query < 0 ? pathAndMore : pathAndMore.substring(0, query);
            String tail = query < 0 ? "" : pathAndMore.substring(query);
            if (unbracketed(host).startsWith("-") || userinfo.startsWith("-")) {
                throw new IllegalArgumentException("the source host or user starts with '-', which ssh would read as"
                        + " an option");
            }
            if (host.isEmpty() && !"file".equals(scheme)) {
                throw new IllegalArgumentException("the source " + scheme + ":// URL names no host");
            }
            if ("file".equals(scheme) && (!(host.isEmpty() || host.equals("localhost"))
                    || authority.indexOf('%') >= 0 || authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0)) {
                // git decodes %xx in the whole URL before it splits the host from the path, and skips a host in
                // brackets: such a host part is a path for git, which the checks of the path would never read
                throw new IllegalArgumentException("the source is a file:// URL with a host other than localhost, or"
                        + " with a '%' or a bracket before its path, which git may read as part of the path; write"
                        + " file:///<path> or give the path itself");
            }
            if ("file".equals(scheme) && !tail.isEmpty()) {
                // The checks read the path up to here, but git opens all of it as the path. Nothing of the URL is
                // repeated: it may hold a credential
                throw new IllegalArgumentException("the source is a file:// URL with a '?' or '#', which git reads as"
                        + " part of the path while the checks of the path stop there; give the path itself, or"
                        + " write the character as %3F or %23");
            }
            if (path.isEmpty() || path.equals("/")) {
                throw new IllegalArgumentException("the source " + scheme + "://" + host + " names no repository"
                        + " path");
            }
            String user = userinfo.contains(":") ? userinfo.substring(0, userinfo.indexOf(':')) : userinfo;
            String kept = "ssh".equals(scheme) && !user.isEmpty() ? user + "@" : "";
            String recorded = scheme + "://" + kept + host + path;
            List<String> secrets = new ArrayList<>();
            // git anonymises a URL in its messages by dropping the user info only
            secrets.add(scheme + "://" + host + pathAndMore);
            secrets.add(userinfo);
            if (userinfo.contains(":")) {
                secrets.add(userinfo.substring(userinfo.indexOf(':') + 1));
            }
            for (String part : tail.split("[?#]")) {
                secrets.add(part);
            }
            secrets.removeIf(part -> part.length() < MIN_SECRET || part.equals(recorded));
            return new Source(source, recorded, false, List.copyOf(secrets),
                    "file".equals(scheme) ? percentDecoded(path) : null);
        }

        /**
         * {@code %xx} decoded as git decodes the path of a {@code file://} URL; a {@code %} without two hex digits
         * stays.
         */
        private static String percentDecoded(String path) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] raw = path.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == '%' && i + 2 < raw.length && hex(raw[i + 1]) >= 0 && hex(raw[i + 2]) >= 0) {
                    bytes.write(hex(raw[i + 1]) * 16 + hex(raw[i + 2]));
                    i += 2;
                } else {
                    bytes.write(raw[i]);
                }
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private static int hex(byte b) {
            return Character.digit((char) b, 16);
        }

        /** A host without the brackets git takes off it ({@code [::1]}, {@code [host]}) before ssh gets it. */
        private static String unbracketed(String host) {
            return host.startsWith("[") ? host.substring(1) : host;
        }

        private static int indexOfAny(String text, char a, char b) {
            int first = text.indexOf(a);
            int second = text.indexOf(b);
            if (first < 0) return second;
            if (second < 0) return first;
            return Math.min(first, second);
        }

        /**
         * {@code text} with the source as given, and as git anonymises it, replaced by the recorded one, and every
         * other secret part replaced by {@code ***}.
         */
        String scrub(String text) {
            if (text == null || given.equals(recorded)) {
                return text;
            }
            String scrubbed = text.replace(given, recorded);
            List<String> parts = new ArrayList<>(secrets);
            parts.sort(Comparator.comparingInt(String::length).reversed());
            for (String part : parts) {
                scrubbed = scrubbed.replace(part, part.contains("://") ? recorded : "***");
            }
            return scrubbed;
        }
    }
}
