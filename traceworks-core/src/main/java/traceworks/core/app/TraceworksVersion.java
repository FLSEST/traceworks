package traceworks.core.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The version of Traceworks that runs, as the build wrote it into {@code traceworks-version.properties}. */
public final class TraceworksVersion {

    private static final String RESOURCE = "/traceworks-version.properties";
    private static final String CURRENT = load();

    private TraceworksVersion() {
    }

    /** The version, such as {@code 0.1.0}; {@code unknown} when the build did not write it. */
    public static String current() {
        return CURRENT;
    }

    private static String load() {
        try (InputStream in = TraceworksVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").strip();
            return version.isEmpty() || version.contains("${") ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
