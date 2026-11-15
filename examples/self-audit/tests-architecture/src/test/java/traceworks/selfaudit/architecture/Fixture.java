package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Scan.Model;
import traceworks.selfaudit.architecture.Sources.Loaded;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A small source tree in a temporary directory, and the tool's steps over it. */
final class Fixture {

    final Path root;

    Fixture(Path root) {
        this.root = root;
    }

    Path sources() throws IOException {
        return Files.createDirectories(root.resolve("mod/src/main/java"));
    }

    /** Writes a source file below {@code mod/src/main/java}. */
    Fixture write(String path, String content) throws IOException {
        Path file = sources().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return this;
    }

    Loaded load() throws IOException {
        return Sources.load(root, List.of(sources()));
    }

    Model model(String rules) throws IOException {
        return Scan.of(load(), RuleFile.parse(rules));
    }

    Facts.Result facts(String rules, Facts.Limits limits) throws IOException {
        RuleFile ruleFile = RuleFile.parse(rules);
        return Facts.of(Scan.of(load(), ruleFile), ruleFile, limits);
    }

    /** A rule file that names no package of the fixtures, for a scan that needs one. */
    static final String NO_RULES = "layer none\n  why nothing\n  from zzz\n  not yyy\n";
}
