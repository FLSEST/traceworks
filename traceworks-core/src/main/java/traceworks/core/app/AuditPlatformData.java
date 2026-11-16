package traceworks.core.app;

import traceworks.core.model.AuditConfig;
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
 * workflow that created the register must be the one that runs. It reads only; {@link Registers} feeds the register at
 * the end of the run.
 */
final class AuditPlatformData {

    private final RegisterStore registers;
    private final Clock clock;

    AuditPlatformData(RegisterStore registers, Clock clock) {
        this.registers = registers;
        this.clock = clock;
    }

    /**
     * The platform data of an audit workflow: {@code audit_findings}, {@code audit_items} and {@code previous_report} from its register
     * (docs/registers.md); nothing for any other workflow, which keeps the literal placeholders and is refused by the
     * analyzer for {@code previous_report}. Read in the start check, before the run exists: a register that cannot
     * be read, or that belongs to another workflow, refuses the run and gets no line.
     */
    Read platformData(LoadedPackage pkg, Preflight preflight) {
        AuditConfig config = AuditConfig.of(pkg.definition().metadata()).orElse(null);
        if (config == null) {
            return Read.NONE;
        }
        try {
            Read data = platformData(pkg, config);
            String owner = registerOwner(config.key());
            if (owner != null && !owner.equals(pkg.definition().id())) {
                preflight.refuse("the register " + config.key() + " belongs to the workflow " + owner + ", not to "
                        + pkg.definition().id());
            }
            return data;
        } catch (RuntimeException e) {
            preflight.refuse("the register " + config.key() + " cannot be read: " + e.getMessage()
                    + "; 'traceworks findings " + config.key() + " verify' says what is wrong");
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
    }

    /**
     * The files a register hands a run ({@link RegisterFiles}), and where its log stood when they were made.
     *
     * @param seq   the sequence number of the last line of the log; {@code null} when the register does not exist yet
     * @param head  the SHA-256 of that line; {@code null} when the register does not exist yet
     * @param files the content of each file, by its name
     */
    record Handed(String key, Integer seq, String head, Map<String, byte[]> files) {
    }

    /** The workflow that created the register; {@code null} when there is no register yet. */
    private String registerOwner(String key) {
        if (!registers.keys().contains(key)) {
            return null;
        }
        try (RegisterStore.Opened opened = registers.open(key)) {
            for (traceworks.core.register.RegisterEvent event : new RegisterLog(opened, clock).read()) {
                if (traceworks.core.register.RegisterEvents.CREATED.equals(event.type())) {
                    return event.text("workflow_id");
                }
            }
            return null;
        }
    }

    private Read platformData(LoadedPackage pkg, AuditConfig config) {
        Catalog catalog = null;
        if (pkg.catalogJson() != null) {
            try {
                catalog = Catalog.parse(pkg.catalogJson());
            } catch (IllegalArgumentException e) {
                catalog = null;   // the start check refuses the run for it
            }
        }
        if (!registers.keys().contains(config.key())) {
            return new Read(Map.of("audit_findings", FindingsText.NONE, "previous_report", PreviousReport.NONE,
                    "audit_items", traceworks.core.register.ItemsText.NONE),
                    new Handed(config.key(), null, null, RegisterFiles.none()));
        }
        try (RegisterStore.Opened opened = registers.open(config.key())) {
            RegisterLog log = new RegisterLog(opened, clock);
            List<traceworks.core.register.RegisterEvent> events = log.read();
            RegisterState state = RegisterReplay.replay(config.key(), events, opened.blobs());
            // The last valid report: the last applied one, whatever was rejected after it
            String report = null;
            for (int i = events.size() - 1; i >= 0; i--) {
                String blob = events.get(i).text("report_blob");
                if (traceworks.core.register.RegisterEvents.REPORT_APPLIED.equals(events.get(i).type())
                        && blob != null) {
                    byte[] bytes = opened.blobs().get(blob).orElse(null);
                    if (bytes != null && !blob.equals(EventChain.sha256Hex(bytes))) {
                        // The text goes to the next run whole, as a file: it must be the report that was applied
                        throw new IllegalStateException("seq " + events.get(i).seq() + ": the report blob " + blob
                                + " does not hash to its name");
                    }
                    report = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
                    break;
                }
            }
            Map<String, String> placeholders = Map.of("audit_findings", FindingsText.render(state, catalog),
                    "previous_report", PreviousReport.defused(report),
                    "audit_items", traceworks.core.register.ItemsText.render(state));
            // An empty log is a register that no line was written to: as none
            boolean lines = log.lastSeq() >= 0;
            return new Read(placeholders, new Handed(config.key(), lines ? log.lastSeq() : null,
                    lines ? log.head() : null, RegisterFiles.of(state, catalog, report)));
        }
    }
}
