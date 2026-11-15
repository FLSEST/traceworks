package traceworks.core.register;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.compat.EarlierNames;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The audit result envelope, {@code <!-- traceworks:audit-result:v1 {...} -->}, a full snapshot of an audit's
 * findings at the end of a report. The last readable envelope counts; a replayed earlier report may carry one of its
 * own, and a cut-off own envelope never falls back to it. The envelope is read strictly (docs/workflow-format.md):
 * {@code snapshotComplete} is the JSON boolean, {@code expectedFindings} an integer, the text fields strings, with
 * limits on count and length, and every error is reported, not the first. Optional fields: {@code gone}, the findings
 * the run checked and found gone; and {@code items} with {@code expectedItems}, the items the audit judges, and
 * {@code item} on a finding (docs/registers.md).
 *
 * <p>An envelope under the earlier marker ({@link EarlierNames#AUDIT_ENVELOPE_WORD} in place of
 * {@code traceworks}) is read the same way, so a report written before the marker changed still feeds its register.
 */
public final class AuditResultParser {

    /** The most findings one envelope may carry. */
    public static final int MAX_FINDINGS = 1000;
    /** The field caps: ten times the width the rendering of {@code audit_findings} shows. */
    public static final int RULE_KEY_LIMIT = 2000;
    public static final int SUBJECT_LIMIT = 3000;
    public static final int TITLE_LIMIT = 2000;
    public static final int EVIDENCE_LIMIT = 3000;
    public static final int DESCRIPTION_LIMIT = 20_000;
    /** The most items one envelope may list. */
    public static final int MAX_ITEMS = 1000;
    /** An item's name is shown whole in {@code audit_items}, so it is no longer than a row shows. */
    public static final int ITEM_NAME_LIMIT = 300;
    public static final int ITEM_DIGEST_LIMIT = 128;
    private static final Pattern ITEM_DIGEST = Pattern.compile("[0-9A-Za-z._:=+/-]{1," + ITEM_DIGEST_LIMIT + "}");
    /** How many errors a result lists before {@code and N more}. */
    public static final int MAX_ERRORS = 20;

    static final String MISSING = "Audit result envelope v1 is missing";
    static final String INCOMPLETE = "The last audit result envelope v1 is incomplete or unreadable";

    /** The marker as a regular expression: the one Traceworks writes, or the earlier one. */
    static final String MARKER_REGEX = "(?:traceworks|" + EarlierNames.AUDIT_ENVELOPE_WORD + "):audit-result:v1";
    /** How an envelope begins, even one that never closes: the marker and the JSON start. */
    private static final Pattern ENVELOPE_START = Pattern.compile("<!--\\s*" + MARKER_REGEX + "\\s*\\{");
    /**
     * How an envelope ends: the brace that closes its JSON, then the end of the comment. An envelope reaches from its
     * start to the first end after it, and both are found in one pass over the text each. A lazy {@code \\{.*?}}
     * from every start would scan to the end of the text for each start that never closes, which is quadratic in a
     * report made of such starts, and a report is a step's output: untrusted.
     */
    private static final Pattern ENVELOPE_END = Pattern.compile("}\\s*-->");
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditResultParser() {
    }

    /**
     * What the parser found.
     *
     * @param valid    whether the report holds a readable, complete snapshot; the findings are empty otherwise
     * @param errors   why not, every reason; empty when valid
     * @param markdown the report without its envelopes, trimmed
     * @param gone     the findings the envelope names as checked and gone ({@code "gone"}); empty without the field
     * @param items    the items the envelope lists ({@code "items"}); {@code null} without the field
     * @param itemsComplete whether the envelope vouches for the whole list with {@code expectedItems}: only then
     *                 does an item the list does not hold leave the register
     */
    public record ParseResult(boolean valid, List<String> errors, String markdown, List<ReportedFinding> findings,
                              List<GoneFinding> gone, List<ReportedItem> items, boolean itemsComplete) {
        public ParseResult {
            errors = List.copyOf(errors);
            findings = List.copyOf(findings);
            gone = List.copyOf(gone);
            items = items == null ? null : List.copyOf(items);
        }

        static ParseResult invalid(String markdown, List<String> errors) {
            return new ParseResult(false, errors, markdown, List.of(), List.of(), null, false);
        }
    }

    /** Whether {@code text} holds the start of an envelope: the marker followed by a JSON object start. */
    public static boolean hasEnvelopeStart(String text) {
        return text != null && ENVELOPE_START.matcher(text).find();
    }

    /** The findings of the last readable envelope of {@code reportText}, or why there are none. */
    public static ParseResult parse(String reportText) {
        String source = reportText == null ? "" : reportText;
        Envelope envelope = lastEnvelope(source);
        if (envelope.error() != null) {
            return ParseResult.invalid(withoutEnvelopes(source).strip(), List.of(envelope.error()));
        }
        String markdown = (withoutEnvelopes(source.substring(0, envelope.start()))
                + source.substring(envelope.end())).strip();
        Errors errors = new Errors();
        JsonNode root = envelope.root();
        JsonNode complete = root.get("snapshotComplete");
        if (complete == null || !complete.isBoolean() || !complete.booleanValue()) {
            errors.add("snapshotComplete must be the JSON boolean true");
        }
        JsonNode findingsNode = root.get("findings");
        List<ReportedFinding> findings = new ArrayList<>();
        if (findingsNode == null || !findingsNode.isArray()) {
            errors.add("findings must be an array");
        } else if (findingsNode.size() > MAX_FINDINGS) {
            errors.add("the envelope carries " + findingsNode.size() + " findings, at most " + MAX_FINDINGS
                    + " are read");
        } else {
            for (int i = 0; i < findingsNode.size(); i++) {
                ReportedFinding finding = finding(findingsNode.get(i), i, errors);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        JsonNode expected = root.get("expectedFindings");
        if (expected != null && !expected.isNull()) {
            if (!expected.isIntegralNumber()) {
                errors.add("expectedFindings must be an integer");
            } else if (findingsNode != null && findingsNode.isArray() && expected.asInt() != findingsNode.size()) {
                errors.add("expectedFindings says " + expected.asInt() + ", envelope carries " + findingsNode.size());
            }
        }
        List<GoneFinding> gone = gone(root.get("gone"), errors);
        JsonNode itemsNode = root.get("items");
        boolean listed = itemsNode != null && !itemsNode.isNull();
        List<ReportedItem> items = listed ? items(itemsNode, errors) : null;
        boolean itemsComplete = expectedItems(root.get("expectedItems"), listed ? itemsNode : null, errors);
        if (items != null) {
            Set<String> names = new HashSet<>();
            // As the register will hold them: it reads the masked pipe of a row as the pipe, in a name and on a finding
            items.forEach(item -> names.add(unmasked(item.name())));
            for (int i = 0; i < findings.size(); i++) {
                String item = findings.get(i).item();
                if (item != null && !names.contains(unmasked(item)) && errors.isEmpty()) {
                    // Only for a list that was read whole: an item that failed its own check is named already
                    errors.add("findings[" + i + "].item names " + item + ", which is none of the items");
                }
            }
        }
        if (!errors.isEmpty()) {
            return ParseResult.invalid(markdown, errors.list());
        }
        return new ParseResult(true, List.of(), markdown, findings, gone, items, itemsComplete);
    }

    /**
     * {@code "items": [{"name": ..., "digest": ..., "judged": true}]}: every item the audit judges, each with the
     * digest of what a verdict about it sees and whether this run gave one. The field is optional; an entry
     * may carry more fields, which are not read. A name is one line, since the next run
     * gets it as a row of {@code audit_items}.
     */
    private static List<ReportedItem> items(JsonNode node, Errors errors) {
        List<ReportedItem> items = new ArrayList<>();
        if (!node.isArray()) {
            errors.add("items must be an array");
            return items;
        }
        if (node.size() > MAX_ITEMS) {
            errors.add("the envelope carries " + node.size() + " items, at most " + MAX_ITEMS + " are read");
            return items;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String where = "items[" + i + "]";
            JsonNode entry = node.get(i);
            if (entry == null || !entry.isObject()) {
                errors.add(where + " must be an object");
                continue;
            }
            String name = itemName(entry, where, "name", true, errors);
            if (name != null && !names.add(unmasked(name))) {
                // Two names that differ in the mask only are one item in the register, the later one over the first
                errors.add(where + ".name " + name + " is in the list twice" + (name.equals(unmasked(name)) ? ""
                        : " (as " + unmasked(name) + ": the register reads " + MASKED_PIPE + " as |)"));
                name = null;
            }
            String digest = required(entry, where, "digest", Integer.MAX_VALUE, errors);
            if (digest != null && !ITEM_DIGEST.matcher(digest).matches()) {
                errors.add(where + ".digest must be 1 to " + ITEM_DIGEST_LIMIT
                        + " characters of letters, digits and . _ : = + / -");
                digest = null;
            }
            JsonNode judged = entry.get("judged");
            if (judged == null || !judged.isBoolean()) {
                errors.add(where + ".judged must be the JSON boolean true or false");
            } else if (name != null && digest != null) {
                items.add(new ReportedItem(name, digest, judged.booleanValue()));
            }
        }
        return items;
    }

    /** How {@code {{ audit_findings }}} and {@code {{ audit_items }}} show a pipe; the register reads it back as one. */
    private static final char MASKED_PIPE = '\u00a6';

    /** A name as the register holds it ({@code Reconciler}): the masked pipe is the pipe. */
    private static String unmasked(String name) {
        return name.replace(MASKED_PIPE, '|');
    }

    /** The name of an item, in a list entry or on a finding: a string of one line within {@value #ITEM_NAME_LIMIT}. */
    private static String itemName(JsonNode node, String where, String field, boolean isRequired, Errors errors) {
        String name = isRequired ? required(node, where, field, ITEM_NAME_LIMIT, errors)
                : optional(node, where, field, ITEM_NAME_LIMIT, errors);
        if (name == null) {
            return null;
        }
        if (name.isBlank()) {
            errors.add(where + "." + field + " is blank");
            return null;
        }
        if (name.chars().anyMatch(c -> Character.isISOControl(c) || c == '\u2028' || c == '\u2029')) {
            errors.add(where + "." + field + " holds a control character");
            return null;
        }
        return name;
    }

    /** Whether {@code expectedItems} vouches for the list: given, an integer, and the size of {@code items}. */
    private static boolean expectedItems(JsonNode expected, JsonNode itemsNode, Errors errors) {
        if (expected == null || expected.isNull()) {
            return false;
        }
        if (!expected.isIntegralNumber()) {
            errors.add("expectedItems must be an integer");
        } else if (itemsNode == null) {
            errors.add("expectedItems needs the list items");
        } else if (itemsNode.isArray() && expected.asInt() != itemsNode.size()) {
            errors.add("expectedItems says " + expected.asInt() + ", envelope carries " + itemsNode.size());
        } else {
            return itemsNode.isArray();
        }
        return false;
    }

    /**
     * {@code "gone": [{"ruleKey": ..., "subject": ...}]}: the findings the run checked and found gone. The field is
     * optional; an entry may carry more fields (a reason), which are not read.
     */
    private static List<GoneFinding> gone(JsonNode node, Errors errors) {
        List<GoneFinding> gone = new ArrayList<>();
        if (node == null || node.isNull()) {
            return gone;
        }
        if (!node.isArray()) {
            errors.add("gone must be an array");
            return gone;
        }
        if (node.size() > MAX_FINDINGS) {
            errors.add("the envelope names " + node.size() + " findings as gone, at most " + MAX_FINDINGS + " are read");
            return gone;
        }
        for (int i = 0; i < node.size(); i++) {
            String where = "gone[" + i + "]";
            JsonNode entry = node.get(i);
            if (entry == null || !entry.isObject()) {
                errors.add(where + " must be an object");
                continue;
            }
            String ruleKey = required(entry, where, "ruleKey", RULE_KEY_LIMIT, errors);
            String subject = required(entry, where, "subject", SUBJECT_LIMIT, errors);
            if (ruleKey != null && subject != null) {
                gone.add(new GoneFinding(ruleKey, subject));
            }
        }
        return gone;
    }

    private static ReportedFinding finding(JsonNode node, int index, Errors errors) {
        String where = "findings[" + index + "]";
        if (node == null || !node.isObject()) {
            errors.add(where + " must be an object");
            return null;
        }
        int before = errors.count;
        String ruleKey = required(node, where, "ruleKey", RULE_KEY_LIMIT, errors);
        String subject = required(node, where, "subject", SUBJECT_LIMIT, errors);
        String title = required(node, where, "title", TITLE_LIMIT, errors);
        String severityText = required(node, where, "severity", 100, errors);
        Severity severity = severityText == null ? null : Severity.parse(severityText).orElse(null);
        if (severityText != null && severity == null) {
            errors.add(where + ".severity must be one of CRITICAL, HIGH, MEDIUM, LOW");
        }
        String description = optional(node, where, "description", DESCRIPTION_LIMIT, errors);
        String evidence = optional(node, where, "evidence", EVIDENCE_LIMIT, errors);
        String item = itemName(node, where, "item", false, errors);
        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = node.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isArray()) {
                errors.add(where + ".tags must be an array of strings");
            } else {
                for (int t = 0; t < tagsNode.size(); t++) {
                    JsonNode tag = tagsNode.get(t);
                    if (!tag.isTextual()) {
                        errors.add(where + ".tags[" + t + "] must be a string");
                    } else if (!tag.asText().isBlank()) {
                        tags.add(tag.asText());
                    }
                }
            }
        }
        if (errors.count > before) {
            return null;
        }
        return new ReportedFinding(ruleKey, subject, severity, title, description, evidence, tags, item);
    }

    private static String required(JsonNode node, String where, String field, int limit, Errors errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            errors.add(where + "." + field + " is required");
            return null;
        }
        return text(value, where, field, limit, errors);
    }

    private static String optional(JsonNode node, String where, String field, int limit, Errors errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return text(value, where, field, limit, errors);
    }

    private static String text(JsonNode value, String where, String field, int limit, Errors errors) {
        if (!value.isTextual()) {
            errors.add(where + "." + field + " must be a string");
            return null;
        }
        String text = value.asText();
        if (text.length() > limit) {
            errors.add(where + "." + field + " is " + text.length() + " characters long, at most " + limit
                    + " characters are read");
            return null;
        }
        return text;
    }

    /** The errors of one parse: the first {@value #MAX_ERRORS} in full, the rest counted. */
    private static final class Errors {
        private final List<String> list = new ArrayList<>();
        private int count;

        void add(String error) {
            count++;
            if (list.size() < MAX_ERRORS) {
                list.add(error);
            }
        }

        boolean isEmpty() {
            return count == 0;
        }

        List<String> list() {
            if (count > MAX_ERRORS) {
                List<String> all = new ArrayList<>(list);
                all.add("and " + (count - MAX_ERRORS) + " more");
                return all;
            }
            return list;
        }
    }

    /**
     * The last readable envelope: the search goes from the last start backwards, and a candidate counts only with
     * readable JSON without remainder. A start after the found envelope that does not end readably invalidates the
     * report instead of falling back to an older envelope.
     */
    private static Envelope lastEnvelope(String source) {
        List<Span> starts = spans(ENVELOPE_START, source);
        if (starts.isEmpty()) {
            return Envelope.failed(MISSING);
        }
        List<Span> ends = spans(ENVELOPE_END, source);
        char[] chars = source.toCharArray();
        String unreadable = null;
        for (int i = starts.size() - 1; i >= 0; i--) {
            Span start = starts.get(i);
            Span end = firstEndAfter(ends, start.end());
            if (end == null) {
                continue;
            }
            // The JSON is read in place: a copy for each of very many starts before one end would cost quadratic time
            int json = start.end() - 1;
            JsonNode root;
            try (JsonParser parser = JSON.getFactory().createParser(chars, json, end.start() + 1 - json)) {
                root = JSON.readTree(parser);
                if (parser.nextToken() != null) {
                    throw new JsonParseException(parser, "Trailing token (of type " + parser.currentToken()
                            + ") found after value");
                }
            } catch (JsonProcessingException e) {
                unreadable = unreadable != null ? unreadable : e.getOriginalMessage();
                continue;
            } catch (IOException e) {
                throw new UncheckedIOException(e);   // not for a parser over an array in memory
            }
            if (starts.getLast().start() >= end.end()) {
                break;   // another one begins after it that does not end readably
            }
            if (root == null || !root.isObject()) {
                unreadable = unreadable != null ? unreadable : "the envelope is no JSON object";
                continue;
            }
            return new Envelope(start.start(), end.end(), root, null);
        }
        return Envelope.failed(unreadable != null ? INCOMPLETE + ": " + unreadable : INCOMPLETE);
    }

    /** Where an envelope lies in the text, and what it holds; or why there is none. */
    private record Envelope(int start, int end, JsonNode root, String error) {
        static Envelope failed(String error) {
            return new Envelope(-1, -1, null, error);
        }
    }

    /** A match of a pattern: from {@code start}, to before {@code end}. */
    private record Span(int start, int end) {
    }

    /** Every match of {@code pattern} in {@code text}, in order, found in one pass. */
    private static List<Span> spans(Pattern pattern, String text) {
        List<Span> spans = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.add(new Span(matcher.start(), matcher.end()));
        }
        return spans;
    }

    /** The first end that begins at or after {@code from}: where the envelope that starts before it ends. */
    private static Span firstEndAfter(List<Span> ends, int from) {
        int low = 0;
        int high = ends.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ends.get(middle).start() < from) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low < ends.size() ? ends.get(low) : null;
    }

    /**
     * The text without its complete envelopes, a replayed old one included: each start with the first end after it,
     * from the left, and a start inside an envelope that is cut out is none of its own.
     */
    private static String withoutEnvelopes(String text) {
        List<Span> starts = spans(ENVELOPE_START, text);
        if (starts.isEmpty()) {
            return text;
        }
        List<Span> ends = spans(ENVELOPE_END, text);
        StringBuilder kept = new StringBuilder(text.length());
        int from = 0;
        for (Span start : starts) {
            if (start.start() < from) {
                continue;
            }
            Span end = firstEndAfter(ends, start.end());
            if (end == null) {
                break;   // no later start ends either
            }
            kept.append(text, from, start.start());
            from = end.end();
        }
        return kept.append(text, from, text.length()).toString();
    }
}
