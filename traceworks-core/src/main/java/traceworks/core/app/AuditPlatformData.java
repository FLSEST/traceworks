package traceworks.core.app;

import traceworks.core.model.AuditConfig;
import traceworks.core.port.Clock;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.RegisterStore;
import traceworks.core.register.Catalog;
import traceworks.core.register.FindingsText;
import traceworks.core.register.PreviousReport;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterReplay;
import traceworks.core.register.RegisterState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What an audit workflow's run gets from its register, read in the start check: the register's log is replayed into
 * the findings it knows ({@code audit_findings}) and the items it knows ({@code audit_items}), its last applied report
 * is found ({@code previous_report}), and the
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
    Map<String, String> platformData(LoadedPackage pkg, Preflight preflight) {
        AuditConfig config = AuditConfig.of(pkg.definition().metadata()).orElse(null);
        if (config == null) {
            return Map.of();
        }
        try {
            Map<String, String> data = platformData(pkg, config);
            String owner = registerOwner(config.key());
            if (owner != null && !owner.equals(pkg.definition().id())) {
                preflight.refuse("the register " + config.key() + " belongs to the workflow " + owner + ", not to "
                        + pkg.definition().id());
            }
            return data;
        } catch (RuntimeException e) {
            preflight.refuse("the register " + config.key() + " cannot be read: " + e.getMessage()
                    + "; 'traceworks findings " + config.key() + " verify' says what is wrong");
            return Map.of();
        }
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

    private Map<String, String> platformData(LoadedPackage pkg, AuditConfig config) {
        Catalog catalog = null;
        if (pkg.catalogJson() != null) {
            try {
                catalog = Catalog.parse(pkg.catalogJson());
            } catch (IllegalArgumentException e) {
                catalog = null;   // the start check refuses the run for it
            }
        }
        if (!registers.keys().contains(config.key())) {
            return Map.of("audit_findings", FindingsText.NONE, "previous_report", PreviousReport.NONE,
                    "audit_items", traceworks.core.register.ItemsText.NONE);
        }
        try (RegisterStore.Opened opened = registers.open(config.key())) {
            List<traceworks.core.register.RegisterEvent> events = new RegisterLog(opened, clock).read();
            RegisterState state = RegisterReplay.replay(config.key(), events, opened.blobs());
            // The last valid report: the last applied one, whatever was rejected after it
            String previous = PreviousReport.NONE;
            for (int i = events.size() - 1; i >= 0; i--) {
                if (traceworks.core.register.RegisterEvents.REPORT_APPLIED.equals(events.get(i).type())
                        && events.get(i).text("report_blob") != null) {
                    previous = opened.blobs().get(events.get(i).text("report_blob"))
                            .map(bytes -> PreviousReport.defused(new String(bytes, StandardCharsets.UTF_8)))
                            .orElse(PreviousReport.NONE);
                    break;
                }
            }
            return Map.of("audit_findings", FindingsText.render(state, catalog), "previous_report", previous,
                    "audit_items", traceworks.core.register.ItemsText.render(state));
        }
    }
}
