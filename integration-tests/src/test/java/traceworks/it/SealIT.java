package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seals (docs/cli.md, "seal, unseal and seals") with the real jar, the real {@code ssh-keygen} and container runs:
 * a sealed package runs and its record names the seal, a sealed package that changed starts no run and no container,
 * and a change in a package it calls refuses it too. The packages are copies in the sandbox, since sealing writes
 * into the package; the key is made in the sandbox.
 */
class SealIT extends IntegrationTest {

    private Path key;

    @BeforeEach
    void keyAndAllowedSigners() throws Exception {
        key = sandbox.resolve("keys").resolve("seal");
        Files.createDirectories(key.getParent());
        Process keygen = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "it@traceworks",
                "-f", key.toString()).redirectErrorStream(true).start();
        assertThat(keygen.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(keygen.exitValue()).isZero();
        traceworks.configure(Map.of("signing_key", key.toString()));
        Files.writeString(traceworks.home().resolve("allowed_signers"),
                "release@example.org " + Files.readString(Path.of(key + ".pub")));
    }

    /** A copy of the test package {@code name} in the sandbox, with every file and directory of it. */
    private Path copyOf(String name) throws IOException {
        Path source = traceworks.packageDir(name);
        Path target = sandbox.resolve("packages").resolve(name);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return target;
    }

    private Result run(Path pkg, String... more) {
        String[] args = new String[4 + more.length];
        args[0] = "run";
        args[1] = pkg.toString();
        args[2] = "--workspace";
        args[3] = traceworks.workspace().toString();
        System.arraycopy(more, 0, args, 4, more.length);
        return traceworks.exec(args);
    }

    @Test
    void aSealedPackage_runsInItsContainers_andTheRecordNamesTheSeal() throws IOException {
        Path pkg = copyOf("placeholders");
        Result sealed = traceworks.exec("seal", pkg.toString());
        assertThat(sealed.code()).as(sealed.describe()).isZero();
        assertThat(pkg.resolve("traceworks.seal")).isRegularFile();

        Result run = run(pkg);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        JsonNode seal = folder.only("run.started").path("seal");
        assertThat(seal.path("principals").get(0).asText()).isEqualTo("release@example.org");
        assertThat(seal.path("signer").asText()).isEqualTo(Traceworks.ACTOR);
        assertThat(seal.path("digest").asText()).hasSize(64)
                .isNotEqualTo(folder.only("run.started").path("package").path("digest").asText());
        assertThat(folder.manifest().path("seal").asText()).isEqualTo("sealed");
        assertThat(folder.manifest().path("seal_digest").asText()).isEqualTo(seal.path("digest").asText());
        Result verify = traceworks.exec("verify", folder.id());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^seal +ok \\(sealed by " + Traceworks.ACTOR);
        // the seal file is in the container's package mount, and the digest of the package does not count it
        Result again = run(pkg);
        assertThat(again.code()).as(again.describe()).isZero();
        assertThat(traceworks.folder(again).only("run.started").path("package").path("digest").asText())
                .isEqualTo(folder.only("run.started").path("package").path("digest").asText());
    }

    @Test
    void aSealedPackageThatChanged_startsNoRun_andNoContainer() throws IOException {
        Path pkg = copyOf("placeholders");
        assertThat(traceworks.exec("seal", pkg.toString()).code()).isZero();
        Files.writeString(pkg.resolve("added.sh"), "echo added\n");
        int runs = traceworks.folders().size();

        Result run = run(pkg);

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("changed since it was sealed");
        assertThat(traceworks.folders()).hasSize(runs);
        Result verify = traceworks.exec("seals", "verify", pkg.toString());
        assertThat(verify.code()).isEqualTo(8);
        Result log = traceworks.exec("seals", "log");
        assertThat(log.code()).isZero();
        assertThat(log.out()).contains("SEALED").contains("REFUSED").contains("reason=changed");

        Files.delete(pkg.resolve("added.sh"));
        assertThat(run(pkg).code()).isZero();
    }

    @Test
    void aChangeInACalledPackageDirectory_breaksTheSealOfTheCaller() throws IOException {
        // "children" calls ./child, a directory of the package: the package's own digest covers it
        Path pkg = copyOf("children");
        Result sealed = traceworks.exec("seal", pkg.toString());
        assertThat(sealed.code()).as(sealed.describe()).isZero();
        Path child = pkg.resolve("child").resolve("workflow.json");
        Files.writeString(child, Files.readString(child) + "\n");

        Result run = run(pkg);

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("changed since it was sealed");
    }

    @Test
    void requireSealAlways_refusesAnUnsealedPackage_andUnsealIsRefusedWhenSealsAreImmutable() throws IOException {
        Path pkg = copyOf("placeholders");
        traceworks.configure(Map.of("signing_key", key.toString(), "require_seal", "always",
                "seal_immutable", true));

        Result unsealed = run(pkg);
        assertThat(unsealed.code()).as(unsealed.describe()).isEqualTo(6);
        assertThat(unsealed.err()).contains("require_seal");

        assertThat(traceworks.exec("seal", pkg.toString()).code()).isZero();
        assertThat(run(pkg).code()).isZero();
        Result unseal = traceworks.exec("unseal", pkg.toString());
        assertThat(unseal.code()).isEqualTo(6);
        assertThat(unseal.err()).contains("seal_immutable");
    }
}
