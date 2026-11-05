package com.minion.core.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.context.LocalMemoryIndex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only, session-scoped original events captured before replacing model context. */
public final class HistoryArchive {
    private static final int CHUNK_CHARS = 700;
    private static final int CHUNK_STEP = 500; // Overlap keeps Chinese terms crossing a chunk boundary searchable.
    private static final int MAX_INDEX_CHARS = 2000000;
    private final Gson gson = new Gson();
    private final Path root;
    private final Path projectRoot;
    // 跨临时 HistoryArchive 实例复用当前会话的派生索引；首次 search 才装载。
    private static final Map<String, SearchCache> SEARCHES = new java.util.LinkedHashMap<String, SearchCache>(8, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, SearchCache> e) { return size() > 8; }
    };

    public HistoryArchive(Path sessionDirectory, String sessionId) {
        this(sessionDirectory, sessionId, null);
    }

    public HistoryArchive(Path sessionDirectory, String sessionId, Path projectRoot) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid session ID");
        root = sessionDirectory.resolve(".history").resolve(sessionId);
        this.projectRoot = projectRoot;
    }

    /** Record the entire pre-compaction event stream, including messages kept in the model context. */
    public int recordCompaction(List<Message> before, List<Message> after, String taskId) throws IOException {
        Set<Message> kept = Collections.newSetFromMap(new IdentityHashMap<Message, Boolean>());
        kept.addAll(after);
        int removed = 0;
        for (Message message : before) {
            if (!kept.contains(message) && !message.pinned
                    && message.role != Message.Role.SYSTEM) removed++;
        }
        if (removed == 0) return 0;
        Files.createDirectories(root);
        Map<String,String> seen = archivedEventHashes();
        List<Message> originals = new ArrayList<Message>();
        for (Message message : before) {
            if (message == null || message.role == Message.Role.SYSTEM) continue;
            if (message.eventId == null || message.eventId.trim().isEmpty())
                message.eventId = UUID.randomUUID().toString();
            String currentHash = sha256(gson.toJson(message));
            String priorHash = seen.get(message.eventId);
            if (priorHash != null && !priorHash.equals(currentHash)) {
                // A live message changed after its earlier snapshot: retain both immutable revisions.
                message.eventId = UUID.randomUUID().toString();
                currentHash = sha256(gson.toJson(message));
            } else if (priorHash != null) continue;
            originals.add(gson.fromJson(gson.toJson(message), Message.class));
            seen.put(message.eventId, currentHash);
        }
        if (originals.isEmpty()) return removed;
        String segmentId = UUID.randomUUID().toString();
        Segment segment = new Segment();
        segment.id = segmentId;
        segment.taskId = taskId;
        segment.archivedAt = System.currentTimeMillis();
        segment.messages = originals;
        segment.hashes = new ArrayList<String>();
        Index index = new Index();
        index.segmentId = segmentId;
        index.taskId = taskId;
        index.archivedAt = segment.archivedAt;
        index.chunks = new ArrayList<Chunk>();
        index.eventIds = new ArrayList<String>();
        Map<String,List<String>> callPaths = new HashMap<String,List<String>>();
        for (Message m : originals) if (m.toolCalls != null) for (ToolCall call : m.toolCalls)
            if (call != null && call.id != null) callPaths.put(call.id, pathsFromArguments(call.arguments));
        for (int i = 0; i < originals.size(); i++) {
            Message m = originals.get(i);
            index.eventIds.add(m.eventId);
            segment.hashes.add(sha256(gson.toJson(m)));
            // Preserve prior summary lineage, but never retrieve a generated summary as evidence.
            if (m.summary || m.pinned) continue;
            List<String> paths = new ArrayList<String>();
            if (m.toolCalls != null) for (ToolCall call : m.toolCalls)
                if (call != null) paths.addAll(pathsFromArguments(call.arguments));
            if (m.toolCallId != null && callPaths.containsKey(m.toolCallId))
                paths.addAll(callPaths.get(m.toolCallId));
            paths = new ArrayList<String>(new LinkedHashSet<String>(paths));
            List<FileStamp> stamps = captureStamps(paths, m.ts);
            StringBuilder searchable = new StringBuilder(m.content == null ? "" : m.content);
            if (m.toolCalls != null) for (com.minion.core.llm.ToolCall call : m.toolCalls) {
                if (call == null) continue;
                searchable.append("\n工具: ").append(call.name).append(' ')
                        .append(call.arguments == null ? "" : call.arguments);
            }
            String body = searchable.toString();
            int half = MAX_INDEX_CHARS / 2;
            int[] starts = body.length() > MAX_INDEX_CHARS ? new int[]{0, body.length() - half} : new int[]{0};
            for (int range = 0; range < starts.length; range++) {
                int end = range == 0 && starts.length == 2 ? half : body.length();
                for (int pos = starts[range]; pos < end; pos += CHUNK_STEP) {
                Chunk c = new Chunk();
                c.messageId = segmentId + "#" + i;
                c.eventId = m.eventId;
                c.taskId = m.taskId == null ? taskId : m.taskId;
                c.role = m.role == null ? "unknown" : m.role.name().toLowerCase(Locale.ROOT);
                c.tool = m.name;
                c.time = m.ts;
                c.offset = pos;
                c.filePaths = paths;
                c.fileStamps = stamps;
                c.text = body.substring(pos, Math.min(end, pos + CHUNK_CHARS));
                index.chunks.add(c);
                }
            }
        }
        segment.indexHash = sha256(gson.toJson(index));
        // A failed archive must not permit compaction to discard the originals.
        writeAtomically(root.resolve(segmentId + ".json"), gson.toJson(segment));
        writeAtomically(root.resolve(segmentId + ".idx.json"), gson.toJson(index));
        return removed;
    }

    private Map<String,String> archivedEventHashes() throws IOException {
        Map<String,String> hashes = new HashMap<String,String>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.idx.json")) {
            for (Path file : files) {
                try {
                    Index index = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Index.class);
                    if (index == null || !validSegmentId(index.segmentId, file))
                        throw new IOException("History index is damaged: " + file);
                    if (index.eventIds == null) continue; // Legacy archive, without event IDs.
                    Path segmentFile = root.resolve(index.segmentId + ".json");
                    if (!Files.isRegularFile(segmentFile)) throw new IOException("History segment is missing: " + segmentFile);
                    Segment segment = gson.fromJson(new String(Files.readAllBytes(segmentFile), StandardCharsets.UTF_8), Segment.class);
                    if (segment == null || segment.messages == null || segment.hashes == null
                            || segment.messages.size() != index.eventIds.size()
                            || segment.hashes.size() != segment.messages.size())
                        throw new IOException("History segment/index mismatch: " + segmentFile);
                    if (segment.indexHash == null || !segment.indexHash.equals(sha256(gson.toJson(index))))
                        throw new IOException("History index checksum mismatch: " + file);
                    for (int i = 0; i < segment.messages.size(); i++) {
                        Message message = segment.messages.get(i);
                        if (message == null || !index.eventIds.get(i).equals(message.eventId)
                                || !segment.hashes.get(i).equals(sha256(gson.toJson(message))))
                            throw new IOException("History event checksum mismatch: " + segmentFile);
                        hashes.put(message.eventId, segment.hashes.get(i));
                    }
                } catch (RuntimeException bad) { throw new IOException("History index is damaged: " + file, bad); }
            }
        }
        return hashes;
    }

    static List<String> pathsFromArguments(String arguments) {
        List<String> out = new ArrayList<String>();
        if (arguments == null || arguments.length() > 100000) return out;
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            for (String key : new String[]{"path", "paths", "outputPath", "otherPath", "file", "source"}) {
                JsonElement value = args.get(key);
                if (value == null || value.isJsonNull()) continue;
                if (value.isJsonPrimitive()) {
                    String s = value.getAsString();
                    if (!s.trim().isEmpty() && s.length() <= 1024) out.add(s);
                } else if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (out.size() >= 100) break;
                        if (item.isJsonPrimitive() && item.getAsString().length() <= 1024
                                && !item.getAsString().trim().isEmpty()) out.add(item.getAsString());
                    }
                }
            }
        } catch (RuntimeException ignored) { /* Unstructured tool arguments are still preserved in full. */ }
        return out;
    }

    private List<FileStamp> captureStamps(List<String> paths, long eventTime) {
        List<FileStamp> out = new ArrayList<FileStamp>();
        if (projectRoot == null) return out;
        try {
            Path rootReal = projectRoot.toRealPath();
            for (String raw : paths) {
                try {
                    Path path = Paths.get(raw);
                    if (!path.isAbsolute()) path = rootReal.resolve(path);
                    path = path.toRealPath();
                    if (!path.startsWith(rootReal) || !Files.isRegularFile(path)) continue;
                    FileStamp stamp = new FileStamp();
                    stamp.path = path.toString();
                    stamp.projectRoot = rootReal.toString();
                    stamp.size = Files.size(path);
                    stamp.modifiedAt = Files.getLastModifiedTime(path).toMillis();
                    // A source changed after the tool result but before compaction is already stale.
                    stamp.staleAtCapture = eventTime > 0 && stamp.modifiedAt > eventTime;
                    stamp.unknownEventTime = eventTime <= 0;
                    if (stamp.size <= 50L * 1024 * 1024) stamp.sha256 = fileHash(path);
                    out.add(stamp);
                } catch (Exception ignored) { /* A path hint is not proof that a file exists. */ }
            }
        } catch (IOException ignored) { /* Missing workspace: retain event, mark source unverified. */ }
        return out;
    }

    private static String sourceState(List<FileStamp> stamps) {
        if (stamps == null || stamps.isEmpty()) return "unverified";
        boolean unknownTime = false;
        for (FileStamp stamp : stamps) {
            if (stamp.staleAtCapture) return "changed";
            unknownTime |= stamp.unknownEventTime || stamp.sha256 == null;
            try {
                Path root = Paths.get(stamp.projectRoot).toRealPath();
                Path file = Paths.get(stamp.path).toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file)
                        || Files.size(file) != stamp.size
                        || Files.getLastModifiedTime(file).toMillis() != stamp.modifiedAt
                        || (stamp.sha256 != null && !stamp.sha256.equals(fileHash(file)))) return "changed";
            } catch (Exception e) { return "changed"; }
        }
        return unknownTime ? "unverified" : "unchanged";
    }

    private static String fileHash(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : md.digest()) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    public List<Hit> search(String query, int limit) throws IOException {
        return search(query, limit, null);
    }

    public List<Hit> search(String query, int limit, String taskId) throws IOException {
        return search(query, limit, taskId, null);
    }

    public List<Hit> search(String query, int limit, String taskId, String pathFilter) throws IOException {
        return search(query, limit, taskId, pathFilter, 0, 0, true);
    }

    /** 严格模式只返回来源仍有效的证据；默认兼容入口保留 changed 历史并明确标记。 */
    public List<Hit> search(String query, int limit, String taskId, String pathFilter,
                            long fromTime, long untilTime, boolean includeChanged) throws IOException {
        if (query == null || query.trim().isEmpty() || !Files.isDirectory(root)) return Collections.emptyList();
        SearchCache cache = searchCache();
        synchronized (cache) {
            refreshSearch(cache);
            List<Hit> hits = new ArrayList<Hit>();
            for (LocalMemoryIndex.Match<Candidate> match : cache.index.search(query)) {
                Candidate candidate = match.value;
                Chunk chunk = candidate.chunk;
                String chunkTask = chunk.taskId == null ? candidate.taskId : chunk.taskId;
                if (taskId != null && !taskId.isEmpty() && !taskId.equals(chunkTask)) continue;
                if ((fromTime > 0 && chunk.time < fromTime) || (untilTime > 0 && chunk.time > untilTime)) continue;
                if (pathFilter != null && !pathFilter.trim().isEmpty()) {
                    boolean found = false;
                    if (chunk.filePaths != null) for (String path : chunk.filePaths)
                        if (path != null && LocalMemoryIndex.normalize(path).contains(LocalMemoryIndex.normalize(pathFilter.trim()))) { found = true; break; }
                    if (!found) continue;
                }
                Hit hit = new Hit(); hit.messageId = chunk.messageId; hit.eventId = chunk.eventId;
                hit.taskId = chunkTask; hit.role = chunk.role; hit.tool = chunk.tool;
                hit.time = chunk.time; hit.offset = chunk.offset; hit.filePaths = chunk.filePaths;
                hit.stamps = chunk.fileStamps; hit.snippet = chunk.text;
                hit.exact = match.exact;
                hit.score = (int) Math.round(match.score * 100); hits.add(hit);
            }
            // 明确原文片段查找优先于模糊召回，防止“新要求/原要求”“第一/第二阶段”互串。
            boolean hasExact = false;
            for (Hit hit : hits) if (hit.exact) { hasExact = true; break; }
            if (hasExact) hits.removeIf(h -> !h.exact);
            hits.sort(Comparator.comparingInt((Hit h) -> h.score).reversed()
                    .thenComparing(Comparator.comparingLong((Hit h) -> h.time).reversed()));
            List<Hit> unique = new ArrayList<Hit>();
            Set<String> messages = new java.util.HashSet<String>(), snippets = new java.util.HashSet<String>();
            Map<String, Boolean> integrity = new HashMap<String, Boolean>();
            for (Hit hit : hits) {
                if (messages.contains(hit.messageId)) continue;
                String key = normalize(hit.snippet).replaceAll("\\s+", " ").trim();
                // 不合并不同任务/不同文件的同文证据，避免误丢来源。
                key = String.valueOf(hit.taskId) + "|" + String.valueOf(hit.filePaths) + "|" + key;
                if (snippets.contains(key)) continue;
                String segmentId = hit.messageId.substring(0, hit.messageId.indexOf('#'));
                try {
                    Boolean valid = integrity.get(segmentId);
                    if (valid == null) {
                        valid = validIndex(root.resolve(segmentId + ".idx.json")); integrity.put(segmentId, valid);
                    }
                    if (!valid) continue;
                    String original = read(hit.messageId, hit.offset, hit.snippet.length());
                    if (hit.eventId != null && !original.contains("event=" + hit.eventId + " |")) continue;
                    if (!original.endsWith(hit.snippet)) continue;
                } catch (IOException | RuntimeException damaged) { continue; }
                hit.sourceState = sourceState(hit.stamps);
                hit.currentEvidence = "unchanged".equals(hit.sourceState);
                if (!includeChanged && !hit.currentEvidence) continue;
                messages.add(hit.messageId); snippets.add(key); unique.add(hit);
                if (unique.size() >= Math.max(1, Math.min(20, limit))) break;
            }
            return unique;
        }
    }

    /** 只丢派生索引，原始事件和哈希绝不重写。 */
    public void rebuildSearchIndex() {
        synchronized (SEARCHES) { SEARCHES.remove(root.toAbsolutePath().normalize().toString()); }
    }

    private SearchCache searchCache() {
        synchronized (SEARCHES) {
            String key = root.toAbsolutePath().normalize().toString();
            SearchCache cache = SEARCHES.get(key);
            if (cache == null) { cache = new SearchCache(); SEARCHES.put(key, cache); }
            return cache;
        }
    }

    private void refreshSearch(SearchCache cache) throws IOException {
        Set<String> seen = new java.util.HashSet<String>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.idx.json")) {
            for (Path file : files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString(); seen.add(name);
                String signature = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
                if (signature.equals(cache.signatures.get(name))) continue;
                List<String> old = cache.ids.remove(name);
                if (old != null) for (String id : old) cache.index.remove(id);
                cache.signatures.remove(name);
                try {
                    Index index = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Index.class);
                    if (index == null || index.chunks == null || !validSegmentId(index.segmentId, file)) continue;
                    List<String> ids = new ArrayList<String>();
                    for (Chunk chunk : index.chunks) {
                        if (chunk == null || chunk.text == null || chunk.messageId == null
                                || !chunk.messageId.matches("[0-9a-fA-F-]{36}#[0-9]+")
                                || !chunk.messageId.startsWith(index.segmentId + "#")) continue;
                        String id = chunk.messageId + "@" + chunk.offset;
                        Candidate c = new Candidate(); c.chunk = chunk; c.taskId = index.taskId;
                        cache.index.put(id, chunk.text, c); ids.add(id);
                    }
                    cache.ids.put(name, ids); cache.signatures.put(name, signature);
                } catch (RuntimeException damaged) { /* 单个损坏索引不影响其它段。 */ }
            }
        }
        for (String name : new ArrayList<String>(cache.ids.keySet())) if (!seen.contains(name)) {
            for (String id : cache.ids.remove(name)) cache.index.remove(id);
            cache.signatures.remove(name);
        }
    }

    private static final class Candidate { Chunk chunk; String taskId; }
    private static final class SearchCache {
        final LocalMemoryIndex<Candidate> index = new LocalMemoryIndex<Candidate>();
        final Map<String, String> signatures = new HashMap<String, String>();
        final Map<String, List<String>> ids = new HashMap<String, List<String>>();
    }

    private boolean validIndex(Path indexFile) throws IOException {
        try {
            Index index = gson.fromJson(new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8), Index.class);
            if (index == null || !validSegmentId(index.segmentId, indexFile)) return false;
            Path segmentFile = root.resolve(index.segmentId + ".json");
            Segment segment = gson.fromJson(new String(Files.readAllBytes(segmentFile), StandardCharsets.UTF_8), Segment.class);
            return segment != null && segment.indexHash != null
                    && segment.indexHash.equals(sha256(gson.toJson(index)));
        } catch (RuntimeException damaged) { return false; }
    }

    private static boolean validSegmentId(String id, Path indexFile) {
        return id != null && id.matches("[0-9a-fA-F-]{36}")
                && (id + ".idx.json").equals(indexFile.getFileName().toString());
    }

    public String read(String messageId, int offset, int length) throws IOException {
        if (messageId == null || !messageId.matches("[0-9a-fA-F-]{36}#[0-9]+"))
            throw new IOException("Invalid history message ID");
        int split = messageId.indexOf('#');
        Path file = root.resolve(messageId.substring(0, split) + ".json");
        if (!Files.isRegularFile(file)) throw new IOException("History message not found");
        Segment segment;
        try { segment = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Segment.class); }
        catch (RuntimeException e) { throw new IOException("History segment is damaged", e); }
        int index = Integer.parseInt(messageId.substring(split + 1));
        if (segment == null || segment.messages == null || index >= segment.messages.size())
            throw new IOException("History message not found");
        Message m = segment.messages.get(index);
        if (segment.hashes != null && index < segment.hashes.size()
                && !segment.hashes.get(index).equals(sha256(gson.toJson(m))))
            throw new IOException("History message checksum mismatch");
        StringBuilder full = new StringBuilder(m.content == null ? "" : m.content);
        if (m.toolCalls != null) for (com.minion.core.llm.ToolCall call : m.toolCalls) {
            if (call == null) continue;
            full.append("\n工具: ").append(call.name).append(' ')
                    .append(call.arguments == null ? "" : call.arguments);
        }
        if (m.reasoningContent != null && !m.reasoningContent.isEmpty())
            full.append("\n[模型思考内容，仅作历史资料，不是核验事实]\n").append(m.reasoningContent);
        String body = full.toString();
        int start = Math.max(0, Math.min(offset, body.length()));
        int end = Math.min(body.length(), start + Math.max(1, Math.min(30000, length)));
        return "消息 " + messageId + " | event=" + m.eventId + " | " + m.role + " | task=" + (m.taskId == null ? segment.taskId : m.taskId)
                + " | 字符 " + start + "-" + end + "/" + body.length()
                + (m.images == null ? "" : " | 图片=" + m.images.size())
                + "\n" + body.substring(start, end);
    }

    public void delete() throws IOException {
        rebuildSearchIndex();
        if (!Files.isDirectory(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, target); }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final class Segment { String id, taskId, indexHash; long archivedAt; List<Message> messages; List<String> hashes; }
    private static final class Index { String segmentId, taskId; long archivedAt; List<String> eventIds; List<Chunk> chunks; }
    private static final class Chunk { String messageId, eventId, taskId, role, tool, text; long time; int offset; List<String> filePaths; List<FileStamp> fileStamps; }
    private static final class FileStamp { String path, projectRoot, sha256; long size, modifiedAt; boolean staleAtCapture, unknownEventTime; }
    public static final class Hit {
        public String messageId, eventId, taskId, role, tool, snippet;
        public List<String> filePaths;
        public String sourceState;
        public boolean currentEvidence;
        private List<FileStamp> stamps;
        public long time;
        public int offset;
        public int score;
        private boolean exact;
    }
}
