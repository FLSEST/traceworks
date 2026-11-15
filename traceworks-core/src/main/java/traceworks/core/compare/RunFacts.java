package traceworks.core.compare;

import traceworks.core.record.Manifest;
import traceworks.core.record.RunView;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a comparison reads of one finished run, all of it from the run's record.
 *
 * @param view     what the events say
 * @param manifest the run's manifest: the inputs digest, the images with their ids, the models and the version
 * @param inputs   the recorded inputs, as the record holds them (redacted); {@code null} when their blob was deleted
 * @param aiImages the operator's {@code ai_images} as {@code run.started} recorded them, by CLI id
 */
public record RunFacts(RunView view, Manifest manifest, Map<String, Object> inputs, Map<String, String> aiImages) {

    public RunFacts {
        aiImages = aiImages == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(aiImages));
    }
}
