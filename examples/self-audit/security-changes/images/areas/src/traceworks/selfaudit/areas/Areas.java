package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Finder.Hit;
import traceworks.selfaudit.areas.Finder.Member;
import traceworks.selfaudit.areas.Finder.Result;
import traceworks.selfaudit.areas.Finder.SourceFile;
import traceworks.selfaudit.areas.RuleFile.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns hits into the areas an AI step judges. An area starts as the member that holds hits, with every rule that
 * hit in it. A member longer than {@code maxLines}, and a hit outside any member, give windows of {@code context}
 * lines around the hits instead; hits whose windows touch share one, as long as it stays within {@code maxLines}.
 * Neighbouring areas of one file that fit {@code maxLines} together are merged into one, with the code between them,
 * so a class is judged in few calls and with its context; {@code member} then names each member, and
 * {@code firstMember} the first of them, which names the area as an item of the audit ({@link Items}).
 */
final class Areas {

    /** One area: what the prompt shows and what the report checks the answer against. */
    record Area(String id, String file, String member, int start, int end, List<String> rules, List<Hit> hits,
                String why, String code, String firstMember) {

        /** The area's subject for a register: the file and the member. */
        String subject() {
            return file + "#" + member;
        }

        /** The hits, one line each, for the prompt. */
        String hitsText() {
            return hits.stream().map(h -> "line " + h.line() + ": " + h.what() + " (" + h.rule() + ")")
                    .collect(Collectors.joining("\n"));
        }

        /**
         * The area as one JSON object, a line of {@code areas.jsonl}. {@code token} is the run's secret that no prompt
         * shows: it makes the line, and so the {@code foreach} marker of its answer, one that no model can write.
         * {@code fence} is the run's random frame around the code in the prompt, which the audited code cannot close.
         */
        Map<String, Object> toJson(String token, String fence) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("file", file);
            json.put("member", member);
            json.put("subject", subject());
            json.put("start", (long) start);
            json.put("end", (long) end);
            json.put("rules", rules);
            List<Map<String, Object>> hitList = new ArrayList<>();
            for (Hit hit : hits) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rule", hit.rule());
                entry.put("line", (long) hit.line());
                entry.put("what", hit.what());
                entry.put("resolved", hit.resolved());
                hitList.add(entry);
            }
            json.put("hits", hitList);
            json.put("hits_text", hitsText());
            json.put("why", why);
            json.put("code", code);
            json.put("token", token);
            json.put("fence", fence);
            return json;
        }
    }

    private Areas() {
    }

    /** The areas of {@code result}, ordered by file and first line and numbered {@code A001}, {@code A002}, ... */
    static List<Area> of(Result result, RuleFile rules, int maxLines, int context) {
        Map<String, SourceFile> files = result.files().stream()
                .collect(Collectors.toMap(SourceFile::path, Function.identity()));
        Map<String, Rule> byId = rules.rules().stream().collect(Collectors.toMap(Rule::id, Function.identity()));
        List<String> ruleOrder = rules.rules().stream().map(Rule::id).toList();

        // The hits of each member, in the order the finder found them (file, then line)
        Map<String, List<Hit>> byMember = new LinkedHashMap<>();
        for (Hit hit : result.hits()) {
            Member m = hit.member();
            byMember.computeIfAbsent(hit.file() + "\0" + m.start() + "\0" + m.label(), k -> new ArrayList<>()).add(hit);
        }

        record Draft(String file, String member, int start, int end, List<Hit> hits, String first) {
            Draft(String file, String member, int start, int end, List<Hit> hits) {
                this(file, member, start, end, hits, member);
            }
        }
        List<Draft> drafts = new ArrayList<>();
        for (List<Hit> hits : byMember.values()) {
            Hit first = hits.getFirst();
            Member member = first.member();
            int lines = files.get(first.file()).lines().size();
            if (!member.classLevel() && member.end() - member.start() + 1 <= maxLines) {
                drafts.add(new Draft(first.file(), member.label(), member.start(), member.end(), hits));
                continue;
            }
            int low = member.classLevel() ? 1 : member.start();
            int high = member.classLevel() ? lines : member.end();
            List<Hit> sorted = hits.stream().sorted(Comparator.comparingInt(Hit::line)).toList();
            int start = -1;
            int end = -1;
            List<Hit> window = new ArrayList<>();
            for (Hit hit : sorted) {
                int from = Math.max(low, hit.line() - context);
                int to = Math.min(high, hit.line() + context);
                if (!window.isEmpty() && from <= end + 1 && Math.max(end, to) - start + 1 <= maxLines) {
                    end = Math.max(end, to);
                    window.add(hit);
                    continue;
                }
                if (!window.isEmpty()) drafts.add(new Draft(first.file(), member.label(), start, end, window));
                start = from;
                end = to;
                window = new ArrayList<>(List.of(hit));
            }
            drafts.add(new Draft(first.file(), member.label(), start, end, window));
        }
        drafts.sort(Comparator.comparing(Draft::file).thenComparingInt(Draft::start));

        // Neighbouring drafts of one file that fit maxLines together become one area: fewer calls, more context
        List<Draft> merged = new ArrayList<>();
        for (Draft draft : drafts) {
            Draft last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && last.file().equals(draft.file())
                    && Math.max(last.end(), draft.end()) - last.start() + 1 <= maxLines) {
                List<Hit> hits = new ArrayList<>(last.hits());
                hits.addAll(draft.hits());
                hits.sort(Comparator.comparingInt(Hit::line));
                String member = last.member().equals(draft.member()) || last.member().endsWith(", " + draft.member())
                        ? last.member() : last.member() + ", " + draft.member();
                merged.set(merged.size() - 1, new Draft(last.file(), member, last.start(),
                        Math.max(last.end(), draft.end()), hits, last.first()));
            } else {
                merged.add(draft);
            }
        }

        List<Area> areas = new ArrayList<>();
        for (Draft draft : merged) {
            List<String> hitRules = draft.hits().stream().map(Hit::rule).distinct()
                    .sorted(Comparator.comparingInt(ruleOrder::indexOf)).toList();
            String why = hitRules.stream().map(r -> r + ": " + byId.get(r).why()).collect(Collectors.joining("\n"));
            List<String> lines = files.get(draft.file()).lines();
            StringBuilder code = new StringBuilder();
            for (int line = draft.start(); line <= draft.end() && line <= lines.size(); line++) {
                if (!code.isEmpty()) code.append('\n');
                code.append(line).append("| ").append(lines.get(line - 1));
            }
            areas.add(new Area(String.format("A%03d", areas.size() + 1), draft.file(), draft.member(),
                    draft.start(), draft.end(), hitRules, List.copyOf(draft.hits()), why, code.toString(),
                    draft.first()));
        }
        return List.copyOf(areas);
    }
}
