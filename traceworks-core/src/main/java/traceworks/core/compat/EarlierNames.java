package traceworks.core.compat;

import java.util.List;

/**
 * Names from before Traceworks had its own. Traceworks still reads or recognises them, so that reports, workflows and
 * machines from that time keep working, and never writes one. This is the only class that may hold them; each
 * constant names the place that uses it.
 */
public final class EarlierNames {

    private EarlierNames() {
    }

    /**
     * The first word of the earlier marker of an audit result envelope, {@code <word>:audit-result:v1}; the marker
     * Traceworks writes begins with {@code traceworks}. Read by {@code core.register.AuditResultParser}, cut by
     * {@code core.register.PreviousReport} and defused by {@code core.util.UntrustedText}.
     */
    public static final String AUDIT_ENVELOPE_WORD = "mysuricates";

    /**
     * Environment variables of the workflow service Traceworks may be started from: its instance key and keystore
     * password, and the token, API address and label of one of its runs. They belong to that service, so
     * {@code adapters.host.ChildProcessEnv} hands none of them to a child process.
     */
    public static final List<String> SERVICE_ENVIRONMENT = List.of("AUTOMATION_API_KEY", "AUTOMATION_SSL_PASSWORD",
            "MYSURICATES_RUN_TOKEN", "MYSURICATES_API", "MYSURICATES_RUN_ID");

    /**
     * The data directory of that service below the home, whose key files {@code core.analyzer.MountRules} names one
     * by one.
     */
    public static final String SERVICE_DATA_DIRECTORY = ".mysuricates";

    /**
     * Every directory below the home in which that service keeps keys and data; no step may mount one
     * ({@code core.container.HostSecrets}). Paths relative to the home.
     */
    public static final List<String> SERVICE_HOME_SECRETS = List.of(SERVICE_DATA_DIRECTORY, ".autotrigger",
            ".config/mysuricates");
}
