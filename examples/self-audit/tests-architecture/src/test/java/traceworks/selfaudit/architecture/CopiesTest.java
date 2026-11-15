package traceworks.selfaudit.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files this package's tool copies from the security package's tool stay the same apart from the package line, so
 * a fix in one copy that is not made in the other fails the build. Each package keeps its own copy, so each image's
 * build context stays inside its package. {@code Json.java}, {@code Known.java} and {@code States.java} are copied
 * whole; the test reads the security package and changes nothing.
 */
class CopiesTest {

    private static final Path OWN = Path.of("../architecture/images/architecture/src/traceworks/selfaudit/architecture");
    private static final Path SECURITY = Path.of("../security/images/areas/src/traceworks/selfaudit/areas");

    @Test
    void theCopiedFiles_equalTheSecurityPackagesApartFromThePackageLine() throws IOException {
        for (String file : List.of("Json.java", "Known.java", "States.java")) {
            List<String> own = Files.readAllLines(OWN.resolve(file));
            List<String> security = Files.readAllLines(SECURITY.resolve(file));

            assertThat(own.getFirst()).isEqualTo("package traceworks.selfaudit.architecture;");
            assertThat(security.getFirst()).isEqualTo("package traceworks.selfaudit.areas;");
            assertThat(own.subList(1, own.size())).as(file).isEqualTo(security.subList(1, security.size()));
        }
    }
}
