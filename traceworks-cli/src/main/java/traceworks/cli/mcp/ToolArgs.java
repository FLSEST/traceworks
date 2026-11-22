package traceworks.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.model.ProjectKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The arguments of one call, read against the schema of its tool: a name the schema does not have, a missing
 * required one and a value of another type are refused in words a model can act on. What is refused here reaches no
 * command and no use case.
 */
final class ToolArgs {

    /** A run by its id or the first characters of it; never a path, which the command line would read as a folder. */
    private static final Pattern RUN = Pattern.compile("[0-9a-fA-F][0-9a-fA-F-]{3,35}");
    private static final Pattern REGISTER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern FINDING = Pattern.compile("[0-9a-fA-F]{4,64}");

    /** A call the tool refuses: the result is an error the model reads, not a failure of the protocol. */
    static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    private final JsonNode arguments;

    ToolArgs(JsonNode arguments, JsonNode schema) {
        this.arguments = arguments;
        JsonNode properties = schema.path("properties");
        List<String> unknown = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(name -> {
            if (!properties.has(name)) {
                unknown.add(shown(name));
            }
        });
        if (!unknown.isEmpty()) {
            List<String> known = new ArrayList<>();
            properties.fieldNames().forEachRemaining(known::add);
            throw new Refusal("unknown argument" + (unknown.size() == 1 ? " " : "s ") + String.join(", ", unknown)
                    + "; this tool takes " + (known.isEmpty() ? "no argument" : String.join(", ", known)));
        }
        for (JsonNode required : schema.path("required")) {
            JsonNode value = arguments.get(required.asText());
            if (value == null || value.isNull()) {
                throw new Refusal("the argument " + required.asText() + " is required");
            }
        }
    }

    /** A name a client sent, as it may stand in a message: short, and without what is no part of a name. */
    private static String shown(String name) {
        String cut = name.length() > 40 ? name.substring(0, 40) + "..." : name;
        return cut.replaceAll("[^A-Za-z0-9_.-]", "?");
    }

    /** The string of {@code name}; {@code null} when it is not given. */
    String string(String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new Refusal("the argument " + name + " takes a string");
        }
        return value.asText();
    }

    boolean flag(String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new Refusal("the argument " + name + " takes true or false");
        }
        return value.asBoolean();
    }

    int integer(String name, int min, int max, int otherwise) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return otherwise;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw new Refusal("the argument " + name + " takes a whole number from " + min + " to " + max);
        }
        return value.asInt();
    }

    /** The position a list goes on from: the {@code next_cursor} of the page before; 0 without one. */
    int cursor() {
        String cursor = string("cursor");
        if (cursor == null) {
            return 0;
        }
        if (!cursor.matches("[0-9]{1,9}")) {
            throw new Refusal("the argument cursor takes the next_cursor of the page before, as it was given");
        }
        return Integer.parseInt(cursor);
    }

    /** An object of strings, such as the inputs of a run; empty when it is not given. */
    Map<String, String> strings(String name) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode object = arguments.get(name);
        if (object == null || object.isNull()) {
            return values;
        }
        if (!object.isObject()) {
            throw new Refusal("the argument " + name + " takes an object of names and string values");
        }
        object.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new Refusal("the value of " + shown(field.getKey()) + " in " + name + " must be a string");
            }
            if (field.getKey().isEmpty() || field.getKey().indexOf('=') >= 0) {
                throw new Refusal("a name in " + name + " is not empty and holds no '='");
            }
            values.put(field.getKey(), field.getValue().asText());
        });
        return values;
    }

    /** A run by id or prefix. */
    String run(String name) {
        String ref = string(name);
        if (ref != null && !RUN.matcher(ref).matches()) {
            throw new Refusal("the argument " + name + " names a run by its id, or by the first characters of it"
                    + " (at least 4), as 'runs' lists them; a path is not taken");
        }
        return ref;
    }

    String register(String name) {
        String key = string(name);
        if (key != null && !REGISTER.matcher(key).matches()) {
            throw new Refusal("the argument " + name + " takes the key of a register, as 'findings_registers'"
                    + " lists them");
        }
        return key;
    }

    /**
     * A project by its key; {@code null} when it is not given. What is no project key is refused here, in the
     * tool's own words and without repeating it: it never reaches a command, where it could be read as an option
     * or as a part of a path.
     */
    String project(String name) {
        String key = string(name);
        if (key != null && !ProjectKey.matches(key)) {
            throw new Refusal("the argument " + name + " takes a project key, such as 'shop' ('findings_registers'"
                    + " lists the ones that have a register): lower-case letters, digits, '.', '_' and '-',"
                    + " beginning with a letter or a digit (" + ProjectKey.PATTERN + "); a path or a folder is"
                    + " not taken");
        }
        return key;
    }

    String finding(String name) {
        String id = string(name);
        if (id != null && !FINDING.matcher(id).matches()) {
            throw new Refusal("the argument " + name + " takes the id of a finding, or the first characters of it"
                    + " (at least 4), as 'findings_list' gives them");
        }
        return id;
    }
}
