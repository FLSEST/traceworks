package traceworks.core.app;

import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;
import traceworks.core.record.RegisterHandover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The register folder of a run against what the run recorded of it: the folder holds the files {@code run.started}
 * names, each with the digest it names, and nothing else. A hash says that a file is the one that was written, not
 * that what was written is right: the content comes from earlier runs, part of it from a model.
 *
 * @param run      the run whose folder it is
 * @param recorded what {@code run.started} recorded of the folder
 */
record RegisterFolder(StoredRun run, RegisterHandover recorded) {

    /** What the register handed over at the start of a run, as the record names it. */
    static RegisterHandover handover(AuditPlatformData.Handed handed) {
        List<RegisterHandover.File> files = new ArrayList<>();
        handed.files().forEach((name, content) -> files.add(new RegisterHandover.File(name,
                EventChain.sha256Hex(content), content.length)));
        return new RegisterHandover(handed.key(), handed.project(), handed.seq(), handed.head(), files);
    }

    /**
     * What is wrong with the folder; {@code null} when it is as recorded. Every difference is named: a file that is
     * missing, one whose content is another, an entry the run did not start with.
     */
    String problem() {
        Optional<Map<String, String>> found;
        try {
            found = run.registerFileDigests();
        } catch (RuntimeException e) {
            return "the register folder of the run cannot be read: " + e.getMessage();
        }
        if (found.isEmpty()) {
            return "the register folder of the run is missing: run.started names " + recorded.files().size()
                    + " files of the register " + recorded.name() + " in it";
        }
        Map<String, String> present = new TreeMap<>(found.get());
        List<String> problems = new ArrayList<>();
        for (RegisterHandover.File file : recorded.files()) {
            String digest = present.remove(file.name());
            if (digest == null) {
                problems.add(file.name() + " is missing");
            } else if (!digest.equals(file.sha256())) {
                problems.add(file.name() + " is not the file the run started with ("
                        + (StoredRun.NOT_A_FILE.equals(digest) ? digest : "sha256 " + digest) + ", recorded "
                        + file.sha256() + ")");
            }
        }
        present.keySet().forEach(name -> problems.add(name + " is not a file the run started with"));
        return problems.isEmpty() ? null : "the register folder of the run changed: " + String.join("; ", problems);
    }
}
