package traceworks.adapters.sign;

import traceworks.core.port.Signer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Signs with {@code ssh-keygen -Y sign}: the operator's SSH key, which may be a private key file, a public key file
 * whose private half the SSH agent holds, or a key with a passphrase that {@code ssh-keygen} asks for on the
 * terminal. The message is written to a file in a temporary directory of the owner alone, which is removed
 * afterwards; nothing is written next to the key.
 *
 * <p>{@code ssh-keygen} gets what it needs to find the agent and to ask for a passphrase, and nothing else of the
 * environment.
 */
public final class SshKeygenSigner implements Signer {

    /** How long one signature may take: a person may have to type a passphrase or touch a key. */
    public static final long TIMEOUT_MILLIS = 120_000;

    /** What {@code ssh-keygen} gets of the environment: where its tools, the agent and a passphrase prompt are. */
    private static final Set<String> ENVIRONMENT = Set.of("PATH", "HOME", "USER", "LOGNAME", "SSH_AUTH_SOCK",
            "SSH_ASKPASS", "SSH_ASKPASS_REQUIRE", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "TMPDIR", "TERM");
    private static final int MAX_SIGNATURE_BYTES = 64 * 1024;

    private final String executable;
    private final Path configuredKey;
    private final Map<String, String> environment;
    private final long timeoutMillis;

    /**
     * @param configuredKey the operator's {@code signing_key}; {@code null} when none is set
     * @param environment   the environment of Traceworks
     */
    public SshKeygenSigner(Path configuredKey, Map<String, String> environment) {
        this("ssh-keygen", configuredKey, environment, TIMEOUT_MILLIS);
    }

    /** For tests: with another executable and timeout. */
    SshKeygenSigner(String executable, Path configuredKey, Map<String, String> environment, long timeoutMillis) {
        this.executable = executable;
        this.configuredKey = configuredKey;
        this.environment = Map.copyOf(environment);
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String sign(byte[] message, String namespace, String key) {
        Path keyFile = key != null ? Path.of(key) : configuredKey;
        if (keyFile == null) {
            throw new IllegalStateException("no signing key is set: name an SSH key as signing_key in traceworks.json,"
                    + " or pass --key FILE ('ssh-keygen -t ed25519 -f FILE' makes one)");
        }
        if (!Files.isRegularFile(keyFile)) {
            throw new IllegalStateException("the signing key " + keyFile + " does not exist or is no file");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("traceworks-sign-");
            Path file = directory.resolve("message");
            Files.write(file, message);
            ProcessBuilder builder = new ProcessBuilder(List.of(executable, "-Y", "sign", "-f", keyFile.toString(),
                    "-n", namespace, file.toString()))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.keySet().removeIf(name -> !ENVIRONMENT.contains(name));
            environment.forEach((name, value) -> {
                if (ENVIRONMENT.contains(name)) {
                    env.put(name, value);
                }
            });
            env.put("LC_ALL", "C");
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new IllegalStateException(executable + " cannot be started (" + e.getMessage() + "): sealing"
                        + " signs with 'ssh-keygen -Y sign', so install OpenSSH 8.1 or later");
            }
            String output;
            try (InputStream out = process.getInputStream()) {
                Thread reader = null;
                StringBuilder said = new StringBuilder();
                reader = Thread.ofPlatform().daemon().name("ssh-keygen-output").start(() -> {
                    try {
                        said.append(new String(out.readNBytes(MAX_SIGNATURE_BYTES), StandardCharsets.UTF_8));
                        out.transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException e) {
                        // the stream closes when the process is ended
                    }
                });
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    throw new IllegalStateException("ssh-keygen did not finish within " + timeoutMillis / 1000
                            + " seconds and was ended; a key with a passphrase needs a terminal or an SSH agent");
                }
                reader.join(5_000);
                synchronized (said) {
                    output = said.toString();
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            Path signature = directory.resolve("message.sig");
            if (process.exitValue() != 0 || !Files.isRegularFile(signature)) {
                throw new IllegalStateException("ssh-keygen could not sign with the key " + keyFile + " (exit code "
                        + process.exitValue() + "): " + oneLine(output));
            }
            if (Files.size(signature) > MAX_SIGNATURE_BYTES) {
                throw new IllegalStateException("ssh-keygen wrote a signature larger than "
                        + MAX_SIGNATURE_BYTES / 1024 + " KiB");
            }
            return Files.readString(signature, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("signing with ssh-keygen failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("signing with ssh-keygen was interrupted");
        } finally {
            delete(directory);
        }
    }

    private static String oneLine(String output) {
        String text = output.replaceAll("\\p{Cntrl}+", " ").strip();
        return text.isEmpty() ? "it said nothing" : text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // a temporary directory the system removes later
        }
    }
}
