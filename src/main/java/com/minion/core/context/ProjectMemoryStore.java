package com.minion.core.context;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.InputStream;

/** Small, inspectable project memories. Chat history and source documents are not facts here. */
public final class ProjectMemoryStore {
    private final Path entries;
    private final String project;
    private final boolean global;
    private final Gson gson = new Gson();
    private final Path root;
    private static final Object[] MUTATIONS = new Object[64];
    static { for (int i = 0; i < MUTATIONS.length; i++) MUTATIONS[i] = new Object(); }
    private Object mutationLock() {
        String key = entries.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) key = key.toLowerCase(Locale.ROOT);
        return MUTATIONS[(key.hashCode() & 0x7fffffff) % MUTATIONS.length];
    }
    // 构造不读盘；仅缓存派生词项，不缓存核验结果或文件来源状态。
    private static final Map<String, LocalMemoryIndex<Entry>> INDEXES =
            new LinkedHashMap<String, LocalMemoryIndex<Entry>>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, LocalMemoryIndex<Entry>> e) { return size() > 16; }
            };

    public ProjectMemoryStore(Path projectRoot) {
        this(projectRoot, false);
    }

    private ProjectMemoryStore(Path root, boolean global) {
        this.global = global;
        this.root = root.toAbsolutePath().normalize();
        project = global ? "global-user-preferences" : root.toAbsolutePath().normalize().toString();
        entries = root.resolve(".minion").resolve("memory")
                .resolve(global ? "global-entries" : "entries");
    }

    /** App-local user preferences only; never reads another project's private history. */
    public static ProjectMemoryStore global(Path appRoot) {
        return new ProjectMemoryStore(appRoot, true);
    }

    /** Reading and candidate generation are independent machine-local switches. */
    public boolean globalUseEnabled() throws IOException { return globalFlag("global-use.enabled", true); }
    public boolean globalGenerateEnabled() throws IOException { return globalFlag("global-generate.enabled", false); }
    public void setGlobalUseEnabled(boolean enabled) throws IOException { writeGlobalFlag("global-use.enabled", enabled); }
    public void setGlobalGenerateEnabled(boolean enabled) throws IOException { writeGlobalFlag("global-generate.enabled", enabled); }

    private boolean globalFlag(String name, boolean fallback) throws IOException {
        if (!global) throw new IllegalStateException("仅全局记忆支持该开关");
        Path file = entries.getParent().resolve(name);
        if (!Files.isRegularFile(file)) return fallback;
        return "on".equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    }

    private void writeGlobalFlag(String name, boolean enabled) throws IOException {
        if (!global) throw new IllegalStateException("仅全局记忆支持该开关");
        Files.createDirectories(entries.getParent());
        Files.write(entries.getParent().resolve(name),
                (enabled ? "on" : "off").getBytes(StandardCharsets.UTF_8));
    }

    /** Opt-in, deliberately narrow user-profile candidates; never silently verified. */
    public Entry proposeGlobalFromUser(String userText, String sessionId) throws IOException {
        if (!globalGenerateEnabled() || userText == null) return null;
        String text = userText.trim();
        if (text.length() < 6 || text.length() > 240 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || unsafeGlobalText(text)) return null;
        String type;
        if (text.matches("^(?:我是|我的身份是|我的职业是|我是一名)[^。！？!?]{2,80}[。！？!?]?$")) type = "identity";
        else if (text.matches("^(?:我偏好|我喜欢|我习惯|我希望|以后请|今后请|请记住我)[^。！？!?]{2,120}[。！？!?]?$")) type = "user";
        else return null;
        return remember(type, text, "用户会话 " + (sessionId == null ? "未知" : sessionId)
                + " 原文；全局自动候选，待人工核验");
    }

    private static boolean unsafeGlobalText(String text) {
        if (text.length() > 240 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches("(?s).*(?:密码|口令|密钥|私钥|令牌|token|api[_ -]?key|secret|sk-[a-z0-9]{8,}|数据库|连接串|jdbc:|https?://|\\b[a-z]:[/\\\\]|@|\\b1[3-9][0-9]{9}\\b).*"
                ) || text.matches(".*(?:本项目|项目一|客户|合同|内网|工号|身份证号|住址|地址).*" );
    }

    /** Extraction is opt-in per project and produces only unverified candidates. */
    public boolean autoExtractEnabled() throws IOException {
        Path flag = entries.getParent().resolve("auto-extract.enabled");
        return !global && Files.isRegularFile(flag)
                && "on".equals(new String(Files.readAllBytes(flag), StandardCharsets.UTF_8).trim());
    }

    public void setAutoExtractEnabled(boolean enabled) throws IOException {
        if (global) throw new IllegalStateException("全局记忆不能自动提取");
        Files.createDirectories(entries.getParent());
        Files.write(entries.getParent().resolve("auto-extract.enabled"),
                (enabled ? "on" : "off").getBytes(StandardCharsets.UTF_8));
    }

    public Entry proposeFromUser(String userText, String sessionId) throws IOException {
        if (!autoExtractEnabled() || userText == null) return null;
        String text = userText.trim();
        if (text.length() < 6 || text.length() > 400 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) return null;
        if (!text.matches("(?s)^(?:请)?(?:记住|以后|今后|每次|始终|默认)[，,:：\\s]?.*")) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(?:密码|口令|密钥|私钥|api[_ -]?key|secret|sk-[a-z0-9]{8,}).*")) return null;
        return remember("user", text, "用户会话 " + (sessionId == null ? "未知" : sessionId) + " 原文；自动候选，待核验");
    }

    /** Small verified index for a fresh conversation; details remain on disk. */
    public List<Entry> verifiedIndex(int limit) throws IOException {
        return verifiedIndex(limit, new Scope());
    }

    public List<Entry> verifiedIndex(int limit, Scope scope) throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();
        for (Entry entry : evaluated(scope)) {
            if (entry.verified && pathMatches(entry, scope == null ? null : scope.path)
                    && seen.add(LocalMemoryIndex.normalize(entry.text).trim())) out.add(entry);
            if (out.size() >= Math.max(1, Math.min(limit, 20))) break;
        }
        return out;
    }

    public Entry remember(String type, String text, String source) throws IOException {
        return remember(type, text, source, null);
    }

    public Entry remember(String type, String text, String source, Details details) throws IOException {
        synchronized (mutationLock()) {
            String kind = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
            if (!("user".equals(kind) || "feedback".equals(kind) || "identity".equals(kind) || "project".equals(kind)
                    || "decision".equals(kind)
                    || "reference".equals(kind))) throw new IllegalArgumentException("记忆类型须为 identity/user/feedback/project/decision/reference");
            if (!global && "identity".equals(kind)) throw new IllegalArgumentException("身份仅可存入个人全局记忆");
            if (global && !("identity".equals(kind) || "user".equals(kind) || "feedback".equals(kind)))
                throw new IllegalArgumentException("跨项目仅允许身份、个人偏好和用户纠正");
            if (global && text != null && unsafeGlobalText(text))
                throw new IllegalArgumentException("跨项目记忆疑似包含隐私、项目资料或凭据，请仅记录通用身份/偏好");
            if (text == null || text.trim().isEmpty() || text.length() > 4000)
                throw new IllegalArgumentException("记忆内容须为 1–4000 字符");
            if (source == null || source.trim().isEmpty())
                throw new IllegalArgumentException("记忆需要来源（用户明确要求或可核验文件路径）");
            for (Entry previous : list()) {
                if (kind.equals(previous.type) && text.trim().equals(previous.text) && details == null) return previous;
            }
            Entry entry = new Entry();
            entry.id = UUID.randomUUID().toString();
            entry.type = kind;
            entry.text = text.trim();
            entry.source = source.trim();
            entry.project = project;
            entry.createdAt = System.currentTimeMillis();
            entry.updatedAt = entry.createdAt;
            entry.verified = false; // Tool approval authorizes writing; it does not prove a claim is true.
            applyDetails(entry, details);
            Files.createDirectories(entries);
            write(entry);
            return entry;
        }
    }

    public Entry forget(String id) throws IOException {
        synchronized (mutationLock()) {
            Entry entry = rawRead(id);
            Path trash = entries.getParent().resolve(global ? "global-trash" : "trash");
            Files.createDirectories(trash);
            for (String suffix : new String[]{".md", ".json"}) {
                Path file = entries.resolve(id + suffix);
                if (Files.isRegularFile(file))
                    Files.move(file, trash.resolve(id + "-" + System.currentTimeMillis() + suffix));
            }
            return entry;
        }
    }

    public Entry edit(String id, String text, String source) throws IOException {
        return edit(id, text, source, null);
    }

    public Entry edit(String id, String text, String source, Details details) throws IOException {
        synchronized (mutationLock()) {
            Entry entry = rawRead(id);
            if (text == null || text.trim().isEmpty() || text.length() > 4000)
                throw new IllegalArgumentException("记忆内容须为 1–4000 字符");
            if (global && unsafeGlobalText(text))
                throw new IllegalArgumentException("跨项目记忆疑似包含隐私、项目资料或凭据");
            if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("修改记忆需要来源");
            entry.text = text.trim();
            entry.source = source.trim();
            entry.updatedAt = System.currentTimeMillis();
            entry.verified = false; // Revised content needs fresh verification.
            if (details != null) applyDetails(entry, details);
            entry.fingerprints = null;
            write(entry);
            return entry;
        }
    }

    public Entry setVerified(String id, boolean verified) throws IOException {
        synchronized (mutationLock()) {
            Entry entry = rawRead(id);
            if (global && verified && unsafeGlobalText(entry.text))
                throw new IllegalArgumentException("跨项目记忆疑似包含隐私、项目资料或凭据");
            if (verified) {
                if (entry.disabled) throw new IllegalArgumentException("记忆已关闭，请先 enable 再人工核验");
                applyDetails(entry, detailsOf(entry));
                entry.fingerprints = captureSources(entry);
                // 将已确认替代保存在被替代条目；删除/改坏新条目不会使旧结论复活。
                if (entry.supersedes != null) for (String oldId : entry.supersedes) {
                    Entry old = rawRead(oldId);
                    if (old.retiredBy == null) old.retiredBy = new ArrayList<Retirement>();
                    Retirement r = new Retirement(); r.id = id; r.at = System.currentTimeMillis();
                    r.conditions = entry.conditions == null ? null : gson.fromJson(gson.toJson(entry.conditions), Conditions.class);
                    boolean exists = false;
                    for (Retirement prior : old.retiredBy)
                        if (id.equals(prior.id) && gson.toJson(prior.conditions).equals(gson.toJson(r.conditions))) exists = true;
                    if (!exists) { old.retiredBy.add(r); write(old); }
                }
            }
            entry.verified = verified;
            entry.verificationHash = verified ? verificationHash(entry) : null;
            entry.updatedAt = System.currentTimeMillis();
            write(entry);
            return read(id);
        }
    }

    public Entry read(String id) throws IOException {
        for (Entry entry : list()) if (id != null && id.equals(entry.id)) return entry;
        throw new IOException("记忆不存在或 ID 无效");
    }

    private Entry rawRead(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("无效记忆 ID");
        Path file = entries.resolve(id + ".md");
        if (!Files.isRegularFile(file)) file = entries.resolve(id + ".json");
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IOException("记忆不存在");
        try {
            Entry entry = parse(file);
            if (entry == null || !id.equals(entry.id)) throw new IOException("记忆文件损坏");
            if (entry.project != null && !project.equals(entry.project)) throw new IOException("记忆不属于当前项目");
            if (global && !("identity".equals(entry.type) || "user".equals(entry.type) || "feedback".equals(entry.type)))
                throw new IOException("跨项目记忆类型不允许");
            return entry;
        } catch (RuntimeException e) { throw new IOException("记忆文件损坏", e); }
    }

    public List<Entry> list() throws IOException {
        return evaluated(new Scope());
    }

    private List<Entry> rawList() throws IOException {
        LinkedHashMap<String,Entry> found = new LinkedHashMap<String,Entry>();
        if (!Files.isDirectory(entries)) return new ArrayList<Entry>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(entries)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (Files.isSymbolicLink(file) || !name.endsWith(".md") && !name.endsWith(".json")) continue;
                try {
                    Entry entry = parse(file);
                    if (entry != null && entry.id != null && entry.id.matches("[0-9a-fA-F-]{36}")
                            && name.equals(entry.id + (name.endsWith(".md") ? ".md" : ".json"))
                            && (entry.project == null || project.equals(entry.project))
                            && (!global || "identity".equals(entry.type) || "user".equals(entry.type) || "feedback".equals(entry.type))
                            && (name.endsWith(".md") || !found.containsKey(entry.id))) found.put(entry.id, entry);
                } catch (IOException | RuntimeException ignored) { /* One damaged item must not hide the others. */ }
            }
        }
        List<Entry> out = new ArrayList<Entry>(found.values());
        out.sort(Comparator.comparingLong((Entry e) -> e.createdAt).reversed());
        return out;
    }

    public List<Entry> search(String query, int limit) throws IOException {
        return search(query, limit, new Scope());
    }

    public List<Entry> search(String query, int limit, String taskId, String pathFilter) throws IOException {
        Scope scope = new Scope(); scope.taskId = taskId; scope.path = pathFilter;
        return search(query, limit, scope);
    }

    public List<Entry> search(String query, int limit, Scope scope) throws IOException {
        if (query == null || query.trim().isEmpty()) return new ArrayList<Entry>();
        LocalMemoryIndex<Entry> index = index();
        synchronized (index) {
            Set<String> ids = new HashSet<String>();
            for (Entry entry : evaluated(scope)) {
                if (!entry.verified || !pathMatches(entry, scope == null ? null : scope.path)) continue;
                ids.add(entry.id);
                index.put(entry.id, entry.type + " " + entry.text + " " + entry.source + " "
                        + (entry.aliases == null ? "" : String.join(" ", entry.aliases)), entry);
            }
            index.retain(ids);
            List<Entry> out = new ArrayList<Entry>(); Set<String> seen = new HashSet<String>();
            for (LocalMemoryIndex.Match<Entry> match : index.search(query)) {
                Entry entry = match.value; entry.score = (int) Math.round(match.score * 100);
                if (seen.add(LocalMemoryIndex.normalize(entry.text).trim())) out.add(entry);
                if (out.size() >= Math.max(1, Math.min(20, limit))) break;
            }
            return out;
        }
    }

    public void rebuildIndex() { synchronized (INDEXES) { INDEXES.remove(entries.toAbsolutePath().normalize().toString()); } }

    private LocalMemoryIndex<Entry> index() {
        synchronized (INDEXES) {
            String key = entries.toAbsolutePath().normalize().toString();
            LocalMemoryIndex<Entry> index = INDEXES.get(key);
            if (index == null) { index = new LocalMemoryIndex<Entry>(); INDEXES.put(key, index); }
            return index;
        }
    }

    /** 关闭不等于删除；重新启用只恢复候选，必须重新人工核验。 */
    public Entry setDisabled(String id, boolean disabled) throws IOException {
        synchronized (mutationLock()) {
            Entry entry = rawRead(id); entry.disabled = disabled; entry.verified = false;
            entry.updatedAt = System.currentTimeMillis(); write(entry); return read(id);
        }
    }

    public Entry setDetails(String id, Details details) throws IOException {
        synchronized (mutationLock()) {
            Entry entry = rawRead(id); applyDetails(entry, details);
            entry.verified = false; entry.fingerprints = null;
            entry.updatedAt = System.currentTimeMillis(); write(entry); return read(id);
        }
    }

    private void applyDetails(Entry entry, Details details) throws IOException {
        if (details == null) return;
        entry.supersedes = clean(details.supersedes, 30);
        entry.conflicts = clean(details.conflicts, 30);
        entry.aliases = clean(details.aliases, 30);
        entry.sourcePaths = clean(details.sourcePaths, 30);
        entry.conditions = details.conditions == null ? null
                : gson.fromJson(gson.toJson(details.conditions), Conditions.class);
        if (global && (!entry.sourcePaths.isEmpty() || (entry.conditions != null
                && (nonempty(entry.conditions.taskId) || nonempty(entry.conditions.pathPrefix)))))
            throw new IllegalArgumentException("全局偏好不得绑定项目路径/任务或读取私人文件");
        if (global) for (String alias : entry.aliases) if (unsafeGlobalText(alias))
            throw new IllegalArgumentException("全局别名疑似包含隐私、项目资料或凭据");
        Conditions c = entry.conditions;
        if (c != null && (c.validFrom < 0 || c.validUntil < 0
                || (c.validUntil > 0 && c.validFrom >= c.validUntil)))
            throw new IllegalArgumentException("适用时间范围无效");
        if (c != null && nonempty(c.pathPrefix)) {
            Path path = root.resolve(c.pathPrefix).normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("适用路径不能跨项目");
            c.pathPrefix = root.relativize(path).toString();
        }
        for (String path : entry.sourcePaths) resolveSource(path, false);
        for (String id : entry.supersedes) {
            if (entry.id.equals(id) || entry.conflicts.contains(id)) throw new IllegalArgumentException("替代/冲突关系无效");
            rawRead(id);
            if (reaches(id, entry.id, new HashSet<String>())) throw new IllegalArgumentException("替代关系不能成环");
        }
        for (String id : entry.conflicts) {
            if (entry.id.equals(id)) throw new IllegalArgumentException("不能与自身冲突");
            rawRead(id);
        }
    }

    private boolean reaches(String from, String to, Set<String> visited) throws IOException {
        if (from.equals(to)) return true;
        if (!visited.add(from)) return false;
        if (visited.size() > 1024) throw new IOException("替代关系过深，请拆分记忆关系");
        Entry e = rawRead(from);
        if (e.supersedes != null) for (String id : e.supersedes) if (reaches(id, to, visited)) return true;
        return false;
    }

    private static List<String> clean(List<String> values, int limit) {
        List<String> out = new ArrayList<String>();
        if (values == null) return out;
        if (values.size() > limit) throw new IllegalArgumentException("记忆元数据条数过多");
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > 1024)
                throw new IllegalArgumentException("记忆元数据值无效");
            if (!out.contains(value.trim())) out.add(value.trim());
        }
        return out;
    }

    private static Details detailsOf(Entry entry) {
        Details d = new Details(); d.supersedes = entry.supersedes; d.conflicts = entry.conflicts;
        d.aliases = entry.aliases; d.sourcePaths = entry.sourcePaths; d.conditions = entry.conditions; return d;
    }

    private List<Entry> evaluated(Scope supplied) throws IOException {
        Scope scope = supplied == null ? new Scope() : supplied;
        List<Entry> all = rawList();
        Map<String, Entry> byId = new LinkedHashMap<String, Entry>();
        for (Entry e : all) {
            e.sourceState = sourceState(e);
            e.state = e.disabled ? "disabled" : !e.verified ? "pending"
                    : !"unchanged".equals(e.sourceState) ? e.sourceState
                    : !applies(e.conditions, scope) ? "not_applicable" : "verified";
            if ((scope.createdAfter > 0 && e.createdAt < scope.createdAfter)
                    || (scope.createdBefore > 0 && e.createdAt > scope.createdBefore)) e.state = "not_applicable";
            if (e.retiredBy != null) for (Retirement r : e.retiredBy)
                if (retirementApplies(r.conditions, scope)) { e.state = "superseded"; break; }
            byId.put(e.id, e);
        }
        // 兼容已有关系元数据；持久 retirement 负责防止被删除的替代者令旧结论复活。
        for (Entry e : all) if ("verified".equals(e.state) && e.supersedes != null)
            for (String id : e.supersedes) if (byId.containsKey(id)) byId.get(id).state = "superseded";
        Set<String> conflicts = new HashSet<String>();
        for (Entry e : all) if ("verified".equals(e.state) && e.conflicts != null)
            for (String id : e.conflicts) {
                Entry other = byId.get(id);
                if (other != null && "verified".equals(other.state)) { conflicts.add(e.id); conflicts.add(id); }
            }
        for (Entry e : all) {
            if (conflicts.contains(e.id)) e.state = "conflict";
            e.verified = "verified".equals(e.state);
        }
        return all;
    }

    private boolean applies(Conditions c, Scope scope) {
        if (c == null) return true;
        long now = scope.at > 0 ? scope.at : System.currentTimeMillis();
        if (c.validFrom > now || (c.validUntil > 0 && now >= c.validUntil)) return false;
        if (nonempty(c.taskId) && !c.taskId.equals(scope.taskId)) return false;
        return !nonempty(c.pathPrefix) || (nonempty(scope.path) && withinPath(scope.path, c.pathPrefix));
    }

    private boolean retirementApplies(Conditions c, Scope scope) {
        if (c == null) return true;
        Scope known = new Scope(); known.at = scope.at;
        // 无上下文不能证明旧决定仍适用：不回退注入被有条件推翻的旧决定。
        known.taskId = nonempty(scope.taskId) ? scope.taskId : c.taskId;
        known.path = nonempty(scope.path) ? scope.path : c.pathPrefix;
        return applies(c, known);
    }

    private boolean pathMatches(Entry e, String filter) {
        if (!nonempty(filter)) return true;
        if (e.conditions != null && nonempty(e.conditions.pathPrefix) && withinPath(filter, e.conditions.pathPrefix)) return true;
        if (e.sourcePaths != null) for (String path : e.sourcePaths) if (withinPath(path, filter)) return true;
        if (e.fingerprints != null) for (SourceFingerprint stamp : e.fingerprints) if (withinPath(stamp.path, filter)) return true;
        return false;
    }

    private boolean withinPath(String raw, String prefix) {
        try {
            Path file = root.resolve(raw).normalize(), base = root.resolve(prefix).normalize();
            return file.startsWith(root) && base.startsWith(root) && file.startsWith(base);
        } catch (RuntimeException bad) { return false; }
    }

    private static boolean nonempty(String s) { return s != null && !s.trim().isEmpty(); }

    private List<String> sourcePaths(Entry e) {
        List<String> paths = e.sourcePaths == null ? new ArrayList<String>() : new ArrayList<String>(e.sourcePaths);
        if (!global && paths.isEmpty() && nonempty(e.source)) {
            // 仅兼容精确路径来源；自然语言来源不猜测，不扫描正文寻找项目文件。
            try {
                Path possible = root.resolve(e.source).normalize();
                if (!possible.startsWith(root) || Files.isRegularFile(possible)
                        || e.source.matches("(?i).+\\.(?:java|py|json|yaml|yml|md|csv|xlsx|txt|xml|properties|sql|js|ts|html)$"))
                    paths.add(e.source);
            } catch (RuntimeException ignored) { /* 非路径的来源说明。 */ }
        }
        return paths;
    }

    private Path resolveSource(String raw, boolean mustExist) throws IOException {
        if (global) throw new IllegalArgumentException("全局偏好不读取项目文件");
        Path file = root.resolve(raw).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("来源文件不能跨项目");
        if (!mustExist && !Files.exists(file)) return file;
        Path realRoot = root.toRealPath(), real = file.toRealPath();
        if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) throw new IOException("来源文件不在当前项目或不是普通文件");
        if (Files.size(real) > 50L * 1024 * 1024) throw new IOException("来源文件超过50MiB，需使用明确的小型证据文件");
        return real;
    }

    private List<SourceFingerprint> captureSources(Entry e) throws IOException {
        List<SourceFingerprint> out = new ArrayList<SourceFingerprint>();
        for (String raw : sourcePaths(e)) {
            Path file = resolveSource(raw, true);
            SourceFingerprint stamp = new SourceFingerprint(); stamp.path = raw;
            stamp.realPath = file.toString(); stamp.sha256 = fileHash(file);
            stamp.size = Files.size(file); stamp.modifiedAt = Files.getLastModifiedTime(file).toMillis();
            out.add(stamp);
        }
        return out;
    }

    private String sourceState(Entry e) {
        List<String> expected = sourcePaths(e);
        if (!expected.isEmpty() && (e.fingerprints == null || e.fingerprints.size() != expected.size())) return "needs_reverify";
        if (e.fingerprints != null) for (SourceFingerprint stamp : e.fingerprints) {
            try {
                if (!expected.contains(stamp.path)) return "needs_reverify";
                Path file = resolveSource(stamp.path, true);
                if (!file.toString().equals(stamp.realPath) || Files.size(file) != stamp.size
                        || Files.getLastModifiedTime(file).toMillis() != stamp.modifiedAt
                        || !fileHash(file).equals(stamp.sha256)) return "changed";
            } catch (Exception failed) { return "changed"; }
        }
        return "unchanged";
    }

    private static String fileHash(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : md.digest()) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void write(Entry entry) throws IOException {
        Files.createDirectories(entries);
        if (entry.project == null) entry.project = project; // Bind migrated legacy entries to this workspace.
        Path file = entries.resolve(entry.id + ".md");
        // 可修改长期记忆保留修订副本；这不是原始会话事件，更不改历史哈希。
        if (Files.isRegularFile(file)) {
            Path revisions = entries.getParent().resolve(global ? "global-revisions" : "revisions").resolve(entry.id);
            Files.createDirectories(revisions);
            Files.copy(file, revisions.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".md"));
        }
        Entry metadata = gson.fromJson(gson.toJson(entry), Entry.class);
        metadata.text = null;
        metadata.textHash = hash(entry.text);
        String markdown = "# 项目记忆\n\n<!-- MINION-METADATA\n" + gson.toJson(metadata)
                + "\n-->\n\n## 内容\n\n" + entry.text + "\n";
        // 复用已有Windows共享句柄兼容写入；不能仅捕获AtomicMoveNotSupported而漏掉AccessDenied。
        com.minion.core.tools.AtomicFiles.writeText(file,markdown,StandardCharsets.UTF_8);
    }

    private Entry parse(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (file.getFileName().toString().endsWith(".json")) {
            Entry legacy = gson.fromJson(content, Entry.class);
            if (legacy != null && legacy.project == null) legacy.verified = false;
            if (legacy != null) { legacy.location = file.toAbsolutePath().normalize().toString(); legacy.line = 1; }
            return legacy;
        }
        String begin = "<!-- MINION-METADATA\n", end = "\n-->\n\n## 内容\n\n";
        int start = content.indexOf(begin), stop = content.indexOf(end, start + begin.length());
        if (start < 0 || stop < 0) throw new IOException("记忆 Markdown 元数据损坏");
        Entry entry = gson.fromJson(content.substring(start + begin.length(), stop), Entry.class);
        if (entry == null) throw new IOException("记忆 Markdown 元数据损坏");
        entry.text = content.substring(stop + end.length()).trim();
        if (entry.text.isEmpty()) throw new IOException("记忆 Markdown 内容为空");
        if (entry.textHash == null || !entry.textHash.equals(hash(entry.text))) {
            entry.verified = false;
            entry.updatedAt = Files.getLastModifiedTime(file).toMillis();
        }
        if (global && unsafeGlobalText(entry.text)) entry.verified = false;
        if (entry.verified && entry.verificationHash != null && !entry.verificationHash.equals(verificationHash(entry)))
            entry.verified = false;
        entry.location = file.toAbsolutePath().normalize().toString();
        entry.line = content.substring(0, stop + end.length()).split("\n", -1).length;
        return entry;
    }

    private static String hash(String text) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format("%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private String verificationHash(Entry e) {
        Details details = detailsOf(e);
        return hash(gson.toJson(details) + "\n" + e.type + "\n" + e.text + "\n" + e.source
                + "\n" + e.project + "\n" + gson.toJson(e.fingerprints));
    }

    public static final class Entry {
        public String id, type, text, source, project, textHash, verificationHash;
        public long createdAt, updatedAt;
        public boolean verified;
        public transient int score;
        public List<String> supersedes, conflicts, aliases, sourcePaths;
        public Conditions conditions;
        public List<SourceFingerprint> fingerprints;
        public List<Retirement> retiredBy;
        public boolean disabled;
        public transient String state, sourceState, location;
        public transient int line;
    }

    public static final class Conditions {
        public String taskId, pathPrefix;
        public long validFrom, validUntil;
    }
    public static final class Details {
        public List<String> supersedes, conflicts, aliases, sourcePaths;
        public Conditions conditions;
    }
    public static final class Scope {
        public String taskId, path;
        public long at, createdAfter, createdBefore;
    }
    public static final class SourceFingerprint {
        public String path, realPath, sha256;
        public long size, modifiedAt;
    }
    public static final class Retirement {
        public String id;
        public Conditions conditions;
        public long at;
    }
}
