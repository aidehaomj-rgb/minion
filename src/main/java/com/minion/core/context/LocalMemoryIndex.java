package com.minion.core.context;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 可丢弃的本地倒排索引。调用方惰性装载当前项目/会话，绝不自行扫描磁盘。 */
public final class LocalMemoryIndex<T> {
    private final Map<String, Document<T>> documents = new LinkedHashMap<String, Document<T>>();
    private final Map<String, Map<String, Integer>> postings = new HashMap<String, Map<String, Integer>>();
    private long totalLength;
    private long updates;

    public static final class Document<T> {
        public final String id, text;
        public final T value;
        private final Map<String, Integer> frequencies;
        private final int length;
        public Document(String id, String text, T value) {
            this.id = id; this.text = text == null ? "" : text; this.value = value;
            frequencies = frequencies(this.text);
            int size = 0;
            for (int n : frequencies.values()) size += n;
            length = Math.max(1, size);
        }
        private Document(String id, Document<T> prior, T value) {
            this.id = id; this.text = prior.text; this.value = value;
            frequencies = prior.frequencies; length = prior.length;
        }
    }

    public static final class Match<T> {
        public final T value;
        public final double score;
        public final boolean exact;
        private Match(T value, double score, boolean exact) { this.value = value; this.score = score; this.exact = exact; }
    }

    public synchronized void put(String id, String text, T value) {
        Document<T> previous = documents.get(id);
        // 正文未变化不重新分词，但核验/过滤所需的 payload 必须更新。
        if (previous != null && previous.text.equals(text)) {
            documents.put(id, new Document<T>(id, previous, value));
            return;
        }
        remove(id);
        Document<T> doc = new Document<T>(id, text, value);
        documents.put(id, doc); totalLength += doc.length; updates++;
        for (Map.Entry<String, Integer> term : doc.frequencies.entrySet()) {
            Map<String, Integer> row = postings.get(term.getKey());
            if (row == null) { row = new HashMap<String, Integer>(); postings.put(term.getKey(), row); }
            row.put(id, term.getValue());
        }
    }

    public synchronized void remove(String id) {
        Document<T> doc = documents.remove(id);
        if (doc == null) return;
        totalLength -= doc.length;
        for (String term : doc.frequencies.keySet()) {
            Map<String, Integer> row = postings.get(term);
            row.remove(id);
            if (row.isEmpty()) postings.remove(term);
        }
    }

    public synchronized void retain(Set<String> ids) {
        for (String id : new ArrayList<String>(documents.keySet())) if (!ids.contains(id)) remove(id);
    }

    public synchronized long updateCount() { return updates; }
    public synchronized int size() { return documents.size(); }

    /** 先召回再由调用方做任务/路径/核验过滤；不提前截断导致有效候选被挤掉。 */
    public synchronized List<Match<T>> search(String query) {
        if (query == null || query.trim().isEmpty() || documents.isEmpty()) return Collections.emptyList();
        String exact = normalize(query);
        Map<String, Double> scores = new HashMap<String, Double>();
        Map<String, Integer> matched = new HashMap<String, Integer>();
        Map<String, Integer> terms = frequencies(query);
        double avg = Math.max(1.0, (double) totalLength / documents.size());
        for (String term : terms.keySet()) {
            Map<String, Integer> row = postings.get(term);
            if (row == null) continue;
            double idf = Math.log(1.0 + (documents.size() - row.size() + 0.5) / (row.size() + 0.5));
            for (Map.Entry<String, Integer> hit : row.entrySet()) {
                Document<T> doc = documents.get(hit.getKey());
                double tf = hit.getValue();
                double score = idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * doc.length / avg));
                if (term.startsWith("字:")) score *= 0.12;
                scores.put(hit.getKey(), scores.getOrDefault(hit.getKey(), 0.0) + score);
                if (!term.startsWith("字:") || exact.length() == 1)
                    matched.put(hit.getKey(), matched.getOrDefault(hit.getKey(), 0) + 1);
            }
        }
        List<Match<T>> out = new ArrayList<Match<T>>();
        for (Map.Entry<String, Double> scored : scores.entrySet()) {
            Document<T> doc = documents.get(scored.getKey());
            String body = normalize(doc.text);
            if (!numericUnitsMatch(exact, body)) continue;
            boolean literal = body.contains(exact);
            int units = matched.getOrDefault(scored.getKey(), 0);
            if (!literal && (units == 0 || (terms.size() > 4 && units < 2))) continue;
            out.add(new Match<T>(doc.value, scored.getValue() + (literal ? 4.0 : 0.0), literal));
        }
        out.sort(Comparator.comparingDouble((Match<T> h) -> h.score).reversed());
        return out;
    }

    /** 小而保守的内置同义词；业务别名由条目 aliases 显式提供。 */
    public static String normalize(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return s.replace("unit tests", "单元测试").replace("unit test", "单元测试")
                .replace("单测", "单元测试").replace("资料库", "数据库")
                .replace('\\', '/');
    }

    private static final Pattern NUMBERS = Pattern.compile("[0-9]+");
    private static boolean numericUnitsMatch(String query, String body) {
        Matcher numbers = NUMBERS.matcher(query);
        while (numbers.find())
            if (!Pattern.compile("(?<![0-9])" + numbers.group() + "(?![0-9])").matcher(body).find()) return false;
        return true;
    }

    private static Map<String, Integer> frequencies(String text) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        Matcher words = Pattern.compile("[\\p{L}\\p{N}_]+").matcher(normalize(text));
        while (words.find()) {
            String word = words.group();
            if (word.length() <= 32) add(out, word);
            for (int i = 0; i < word.length(); i++)
                if (Character.UnicodeScript.of(word.charAt(i)) == Character.UnicodeScript.HAN)
                    add(out, "字:" + word.charAt(i));
            // 中文连续句不依赖空格；二字片段可跨中文/数字边界，不拆西文标识符。
            for (int i = 0; i + 1 < word.length(); i++)
                if (Character.UnicodeScript.of(word.charAt(i)) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(word.charAt(i + 1)) == Character.UnicodeScript.HAN)
                    add(out, word.substring(i, i + 2));
        }
        return out;
    }

    private static void add(Map<String, Integer> out, String term) {
        out.put(term, Math.min(20, out.getOrDefault(term, 0) + 1));
    }
}
