package traceworks.core.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One block of a workflow: its {@code type}, an optional {@code name} and its {@code config}.
 *
 * <p>Mutable, because the block JSON is bound to it field by field. Fields of the block JSON besides these three
 * are kept as {@linkplain #unknownFields() unknown fields}.
 */
public class BlockDefinition {

    /** The fields of a block's JSON a run reads; every other one ends up in {@link #unknownFields()}. */
    public static final Set<String> FIELDS = Set.of("type", "name", "config");

    /** The block field that names skills to install for the step; Traceworks has no skills. */
    public static final String SKILL_IDS = "skillIds";

    private String type;

    /**
     * Optional human-readable name used to reference this block's output from later blocks via
     * {@code {{ block.<name>.output }}}. Allowed characters: letters, digits, underscore, hyphen; no dots or
     * whitespace.
     */
    private String name;

    private Map<String, Object> config = new HashMap<>();

    /**
     * Fields of the block's JSON besides the ones above, such as {@code "on_failure": "continue"} next to
     * {@code config} instead of in it. No run reads them; dropping them without a word would let such a setting
     * silently have no effect. They are kept and written back, so the analyzer can report them
     * ({@code unknown-block-field}) wherever the definition came from. Sorted, so a definition hash does not
     * depend on their order.
     */
    @JsonIgnore
    private Map<String, Object> unknownFields = new TreeMap<>();

    public BlockDefinition() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    @JsonAnySetter
    public void putUnknownField(String key, Object value) {
        unknownFields.put(key, value);
    }

    /** The {@linkplain #putUnknownField unknown fields}, read only; empty for a well-formed block. */
    @JsonAnyGetter
    public Map<String, Object> unknownFields() {
        return Collections.unmodifiableMap(unknownFields);
    }

    /**
     * Whether the block asks for skills: an {@linkplain #unknownFields() unknown field} {@value #SKILL_IDS} that is
     * not an empty list. Traceworks has no skills, so the step would run without them.
     */
    public boolean requestsSkills() {
        Object skillIds = unknownFields.get(SKILL_IDS);
        return skillIds != null && !(skillIds instanceof Collection<?> ids && ids.isEmpty());
    }

    /**
     * Whether a failure of this block ends the run (or the switch case it sits in). Fail-closed: only an
     * explicit {@code on_failure: "continue"} (trimmed, any case) lets execution go on. A missing value,
     * {@code "stop"} and every unknown value ({@code "fail"}, a typo, a non-string) stop, so a mistyped setting
     * can never silently turn a failure into a pass. The one place that reads {@code on_failure}; executors
     * call this, never the raw config.
     */
    public boolean shouldStopOnFailure() {
        Object onFailure = config == null ? null : config.get("on_failure");
        return !(onFailure instanceof String value && "continue".equalsIgnoreCase(value.trim()));
    }

    /**
     * Whether {@code onFailure} is one of the two values as {@link #shouldStopOnFailure} reads them, trimmed and in
     * any case: {@code continue} or {@code stop}. The analyzer reports every other value, though the run stops on it.
     */
    public static boolean knownOnFailure(String onFailure) {
        String value = onFailure.trim();
        return "continue".equalsIgnoreCase(value) || "stop".equalsIgnoreCase(value);
    }

    /**
     * Whether the block's AI calls may use the MCP servers of the user's CLI configuration
     * ({@code user_mcp_servers: true}). Only an explicit boolean {@code true} opts in. The one place that reads
     * the key.
     */
    public boolean userMcpServers() {
        return userMcpServers(config);
    }

    /** The same rule for callers that hold only the config map, such as the agentic block's settings. */
    public static boolean userMcpServers(Map<String, Object> config) {
        return config != null && Boolean.TRUE.equals(config.get("user_mcp_servers"));
    }

    /** Builds a block; unless {@link #config} was called, the block gets its own empty mutable config map. */
    public static final class Builder {

        private String type;
        private String name;
        private Map<String, Object> config;
        private boolean configSet;

        private Builder() {
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder config(Map<String, Object> config) {
            this.config = config;
            this.configSet = true;
            return this;
        }

        public BlockDefinition build() {
            BlockDefinition block = new BlockDefinition();
            block.setType(type);
            block.setName(name);
            block.setConfig(configSet ? config : new HashMap<>());
            return block;
        }
    }
}
