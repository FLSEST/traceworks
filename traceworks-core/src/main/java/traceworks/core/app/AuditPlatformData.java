package traceworks.core.app;

import traceworks.core.model.AuditConfig;
import traceworks.core.port.BlobStore;
import traceworks.core.port.Clock;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.RegisterStore;
import traceworks.core.register.Catalog;
import traceworks.core.register.FindingsText;
import traceworks.core.record.EventChain;
import traceworks.core.register.PreviousReport;
import traceworks.core.register.RegisterFiles;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterReplay;
import traceworks.core.register.RegisterState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What an audit workflow's run gets from its register, read in the start check: the register's log is replayed into
 * the findings it knows ({@code audit_findings}) and the items it knows ({@code audit_items}), its last applied report
 * is found ({@code previous_report}), the same is made into the files of the run's register folder
 * ({@link RegisterFiles}), and the
 * workflow that created the register must be the one that runs. A register the ledger anchors whose log is gone
 * refuses the run: it is not begun anew. The register is that of the workflow's key and the
 * run's project, and of no other project: a run sees only what earlier runs on its project left. It reads only;
 * {@link Registers} feeds the register at the end of the run.
 */
final class AuditPlatformData {

    private final RegisterStore registers;
    private final Clock clock;
    private final traceworks.core.port.Ledger ledger;

    /**
     * @param ledger says whether it anchors a register whose log is gone ({@link RegisterWrites#logGone}); {@code
     *               null} for none
     */
    AuditPlatformData(RegisterStore registers, Clock clock, traceworks.core.port.Ledger ledger) {
        this.registers = registers;
        this.clock = clock;
        this.ledger = ledger;
    }

    /**
     * The platform data of an audit workflow: {@code audit_findings}, {@code audit_items} and {@code previous_report} from its register
     * (docs/registers.md); nothing for any other workflow, which keeps the literal placeholders and is refused by the
     * analyzer for {@code previous_report}. Read in the start check, before the run exists: a register that cannot
     * be read, or that belongs to another workflow, refuses the run and gets no line.
     *
     * @param project the project of the run, which says whose register it reads; {@code null} for a run that names
     *                none
     * @param starts  whether a new run is checked, and not a resume. Either needs a project, since there is no
     *                register to read or to feed without one and no default that would not mix two projects: a
     *                new run is refused, and the refusal names both ways to one (the option, and the
     *                {@code projects} setting of the home, which binds a key to the folder a run works in); a
     *                resumed run whose record names none (a run of a version before registers had projects) is
     *                refused too, because every step it still ran, a model's among them, would work for a report
     *                no register takes
     */
    Read platformData(LoadedPackage pkg, String project, boolean starts, Preflight preflight) {
        AuditConfig config = AuditConfig.of(pkg.definition().metadata()).orElse(null);
        if (config == null) {
            return Read.NONE;
        }
        if (project == null) {
            preflight.refuse(starts ? "the workflow feeds the register " + config.key() + ", and a register is kept"
                    + " per project: name the project this run works on with --project KEY, or bind a key to the"
                    + " folder the run works in with this line in traceworks.json of the home: \"projects\":"
                    + " {\"KEY\": \"/the/folder\"}"
                    : "the record of the run names no project, and the workflow feeds the register " + config.key()
                    + ", which is kept per project: its report could feed no register. The run was started before"
                    + " registers had projects; start it again with --project KEY");
            return Read.NONE;
        }
        try {
            if (registers.keys().contains(config.key()) && registers.unassigned(config.key())) {
                // Nothing assigns a register from before projects by itself: not even to the project of this run
                preflight.refuse(RegisterWrites.fromBefore(config.key()));
                return Read.NONE;
            }
            String gone = RegisterWrites.logGone(ledger, registers, config.key(), project);
            if (gone != null) {
                // The ledger knows a log of this register that is not there: nothing is begun in its place
                preflight.refuse(gone);
                return Read.NONE;
            }
            Read data = platformData(pkg, config, project);
            String foreign = Registers.ownerOtherThan(registers, clock, config.key(), pkg.definition().id());
            if (foreign != null) {
                preflight.refuse(foreign);
            }
            return data;
        } catch (RuntimeException e) {
            preflight.refuse("the register " + RegisterOutcome.named(config.key(), project) + " cannot be read: "
                    + e.getMessage() + "; '" + RegisterWrites.command(config.key(), project, "verify")
                    + "' says what is wrong");
            return Read.NONE;
        }
    }

    /**
     * What one read of a register gives a run: the placeholders and the files, both from the same lines of its log.
     *
     * @param placeholders {@code audit_findings}, {@code audit_items} and {@code previous_report}; empty for a
     *                     workflow that is no audit
     * @param register     the files of the run's register folder; {@code null} for a workflow that is no audit
     */
    record Read(Map<String, String> placeholders, Handed register) {

        /** Of a workflow that is no audit, and of a register that refused the run. */
        static final Read NONE = new Read(Map.of(), null);

        /** Of a register that does not exist yet: nothing is known, as before the first report. */
        static final Read UNKNOWN = new Read(Map.of("audit_findings", FindingsText.NONE, "previous_report",
                PreviousReport.NONE, "audit_items", traceworks.core.register.ItemsText.NONE), null);
    }

    /**
     * The files a register hands a run ({@link RegisterFiles}), and where its log stood when they were made.
     *
     * @param project the project the register is of
     * @param seq   the sequence number of the last line of the log; {@code null} when the register does not exist yet
     * @param head  the SHA-256 of that line; {@code null} when the register does not exist yet
     * @param files the content of each file, by its name
     */
    record Handed(String key, String project, Integer seq, String head, Map<String, byte[]> files) {
    }

    /**
     * The blobs of a register, read with a check: a blob whose content does not hash to its name is not the one a
     * line of the log points to, and refuses the run.
     */
    private static BlobStore hashed(BlobStore blobs) {
        return new BlobStore() {
            @Override
            public traceworks.core.port.BlobRef put(byte[] content) {
                throw new UnsupportedOperationException("the start check reads a register and writes nothing");
            }

            @Override
            public java.util.Optional<byte[]> getUnverified(String sha256) {
                java.util.Optional<byte[]> content = blobs.getUnverified(sha256);
                if (content.isPresent() && !sha256.equals(EventChain.sha256Hex(content.get()))) {
                    throw new IllegalStateException("the blob " + sha256 + " does not hash to its name");
                }
                return content;
            }
        };
    }

    private Read platformData(LoadedPackage pkg, AuditConfig config, String project) {
        Catalog catalog = null;
        if (pkg.catalogJson() != null) {
            try {
                catalog = Catalog.parse(pkg.catalogJson());
            } catch (IllegalArgumentException e) {
                catalog = null;   // the start check refuses the run for it
            }
        }
        if (!registers.projects(config.key()).contains(project)) {
            return new Read(Read.UNKNOWN.placeholders(),
                    new Handed(config.key(), project, null, null, RegisterFiles.none()));
        }
        try (RegisterStore.Opened opened = registers.open(config.key(), project)) {
            RegisterLog log = new RegisterLog(opened, clock);
            List<traceworks.core.register.RegisterEvent> events = log.read();
            // What the run is handed, whole and as files, comes from these blobs: each must be what its name says
            BlobStore blobs = hashed(opened.blobs());
            RegisterState state = RegisterReplay.replay(config.key(), project, events, blobs);
            // The last valid report: the last applied one, whatever was rejected after it
            String report = null;
            for (int i = events.size() - 1; i >= 0; i--) {
                String blob = events.get(i).text("report_blob");
                if (traceworks.core.register.RegisterEvents.REPORT_APPLIED.equals(events.get(i).type())
                        && blob != null) {
                    byte[] bytes = blobs.get(blob).orElse(null);
                    report = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
                    break;
                }
            }
            Map<String, String> placeholders = Map.of("audit_findings", FindingsText.render(state, catalog),
                    "previous_report", PreviousReport.defused(report),
                    "audit_items", traceworks.core.register.ItemsText.render(state));
            // An empty log is a register that no line was written to: as none
            boolean lines = log.lastSeq() >= 0;
            return new Read(placeholders, new Handed(config.key(), project, lines ? log.lastSeq() : null,
                    lines ? log.head() : null, RegisterFiles.of(state, catalog, report)));
        }
    }
}
