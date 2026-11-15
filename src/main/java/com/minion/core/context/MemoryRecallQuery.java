package com.minion.core.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded lexical queries, not new instructions or a model-generated summary.
 * Pass the full current requirement, active step and latest real-user correction;
 * do not truncate/concatenate them at the call site. Each input reserves its tail.
 */
public final class MemoryRecallQuery {
    public static final int MAX_QUERY_CHARS = 180;
    public static final int MAX_QUERIES = 12;
    public static final int MAX_TOTAL_CHARS = MAX_QUERY_CHARS * MAX_QUERIES;

    public static final class Segment {
        public final String text, origin;
        public final double weight;
        private Segment(String text, String origin, double weight) {
            this.text = text; this.origin = origin; this.weight = weight;
        }
    }

    private final List<Segment> segments;

    private MemoryRecallQuery(Map<String, Segment> selected) {
        segments = Collections.unmodifiableList(new ArrayList<Segment>(selected.values()));
    }

    public static MemoryRecallQuery from(String currentRequirement) {
        return from(currentRequirement, null, null);
    }

    public static MemoryRecallQuery from(String currentRequirement, String activeStep, String latestCorrection) {
        Map<String, Segment> selected = new LinkedHashMap<String, Segment>();
        // Deduplication keeps the strongest origin; a long goal cannot crowd out a correction/step.
        extract(selected, latestCorrection, "correction", 1.5);
        extract(selected, activeStep, "step", 1.2);
        extract(selected, currentRequirement, "requirement", 1.0);
        return new MemoryRecallQuery(selected);
    }

    public List<Segment> segments() { return segments; }
    public boolean isEmpty() { return segments.isEmpty(); }

    private static void extract(Map<String, Segment> out, String input, String origin, double weight) {
        if (input == null) return;
        String text = input.trim();
        if (text.isEmpty()) return;
        if (text.length() <= MAX_QUERY_CHARS) {
            add(out, text, origin, weight);
            return;
        }
        int tail = text.length() - MAX_QUERY_CHARS;
        // Always preserve the actual suffix, including a final Chinese sentence without whitespace.
        add(out, slice(text, tail, text.length()), origin, weight);
        // A final clause isolates constraints from unrelated numbers elsewhere in the same window.
        int clause = lastClauseStart(text, tail);
        if (clause > tail) add(out, slice(text, clause, text.length()), origin, weight);
        else add(out, window(text, (text.length() - MAX_QUERY_CHARS) / 3), origin, weight);
        add(out, slice(text, 0, MAX_QUERY_CHARS), origin, weight);
        add(out, window(text, (text.length() - MAX_QUERY_CHARS) / 3 * 2), origin, weight);
    }

    private static String window(String text, int start) {
        return slice(text, start, Math.min(text.length(), start + MAX_QUERY_CHARS));
    }

    private static int lastClauseStart(String text, int tail) {
        int end = text.length();
        // Ignore terminal punctuation; it must not turn the final clause into an empty query.
        while (end > tail && (delimiter(text.charAt(end - 1)) || Character.isWhitespace(text.charAt(end - 1)))) end--;
        for (int i = end - 1; i >= tail; i--) if (delimiter(text.charAt(i))) return i + 1;
        return tail;
    }

    private static boolean delimiter(char c) {
        // Do not split dots/slashes/colons inside identifiers, decimals and source paths.
        return c == '\n' || c == '\r' || c == '。' || c == '！' || c == '？'
                || c == '!' || c == '?' || c == '；' || c == ';' || c == '，' || c == ',';
    }

    private static String slice(String text, int start, int end) {
        if (start > 0 && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start - 1))) start++;
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(start, end).trim();
    }

    private static void add(Map<String, Segment> out, String text, String origin, double weight) {
        if (text.isEmpty() || out.size() >= MAX_QUERIES) return;
        String key = LocalMemoryIndex.normalize(text).trim();
        if (!key.isEmpty() && !out.containsKey(key)) out.put(key, new Segment(text, origin, weight));
    }
}
