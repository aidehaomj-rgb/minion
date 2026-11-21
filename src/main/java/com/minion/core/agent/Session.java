package com.minion.core.agent;

import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.UsageTracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 会话状态：消息、任务清单、统计。可整体序列化落盘（字段即 JSON 结构）。 */
public class Session {

    public String id;
    public String createdAt;
    /** 会话标题（GUI 显示用；新建会话由 LLM 摘要生成，恢复旧会话可能为 null） */
    public String title;
    public String workDir;
    public String modelName;
    /** 本会话选择的模型配置标识；旧会话缺失时使用当前默认模型。 */
    public String modelDisplayName;
    /** Exact live model configuration used by the parent client; never serialized or exposed to the model. */
    public transient volatile com.minion.core.config.ModelConfig runtimeModelConfig;
    /** Child defaults are per conversation, not global model settings. */
    public volatile String subAgentModelName;
    /** §1.3 模型降级 override：主模型连续过载降级后本会话沿用该模型（/model 手动切换时清除）；null=无 */
    public volatile String modelOverride;
    public volatile String subAgentMode="worker";
    public volatile String subAgentTools="";
    public volatile String contextProfile="balanced";
    public volatile int preferredContextLimit;
    /** Explicit HTTP context-overflow failures learned for this session; 0 means no verified limit. */
    public volatile int observedContextLimit;
    /** V40: one-time release of legacy learned caps for high-window 150K sessions. */
    public int context150kMigrationVersion;
    /** 会话管理元数据：置顶优先展示；归档后从默认侧栏隐藏但仍保留落盘。 */
    public boolean pinned;
    public boolean archived;
    /** 会话级工作目录(相对路径基准);null/空 = 跟随工作区根 */
    public String cwd;
    public List<Message> messages = new ArrayList<Message>();
    /** Null on old snapshots; reading must not materialize a new serialized field. */
    public List<GeneratedArtifact> artifacts;
    /** Normalize persisted technical receipts before GUI history replay; never mutates raw messages. */
    public void recoverSteeringReceipts() { SteeringTracker.recover(this); }
    /** Nullable for old JSON/checksummed events. UI receipts never replace original user text. */
    public List<SteeringReceipt> steeringReceipts;
    /** Parallel to pendingSupplements; null entries denote pending legacy input without a receipt yet. */
    public List<String> pendingSteeringIds;

    public synchronized List<SteeringReceipt> steeringSnapshot() {
        List<SteeringReceipt> result=new ArrayList<SteeringReceipt>();
        if(steeringReceipts!=null)for(int i=Math.max(0,steeringReceipts.size()-100);i<steeringReceipts.size();i++) {
            SteeringReceipt r=steeringReceipts.get(i);if(r!=null)result.add(r.copy());
        }
        return result;
    }

    /** Detached, bounded receipts, oldest to newest. Never exposes mutable stored entries. */
    public synchronized List<GeneratedArtifact> artifactsSnapshot() {
        List<GeneratedArtifact> result = new ArrayList<GeneratedArtifact>();
        if (artifacts != null) for (int i = Math.max(0, artifacts.size() - 100); i < artifacts.size(); i++) {
            GeneratedArtifact a = artifacts.get(i);
            if (a != null) result.add(a.copy());
        }
        return result;
    }

    /** Caller owns the dispatch fence. Copy-on-write also keeps concurrent JSON readers stable.
     *  快照拷贝（最大 64MB）在锁外执行：锁内只做合并与登记，避免大文件落盘期间阻塞
     *  artifactsSnapshot/steeringSnapshot 等同监视器读取（UI 恢复会话在 FX 线程调用）。 */
    GeneratedArtifact registerArtifact(GeneratedArtifact artifact) {
        if (artifact == null) return null;
        GeneratedArtifact staged = mergeArtifact(artifact);
        if (staged == null) return null;
        staged.snapshotPath = snapshotArtifact(staged);   // 锁外磁盘 IO
        return commitArtifact(staged);
    }

    /** 合并同 path 历史并返回待登记副本（不修改登记表、不做 IO）；REFERENCE 覆盖正式产出时拒绝返回 null */
    private synchronized GeneratedArtifact mergeArtifact(GeneratedArtifact artifact) {
        List<GeneratedArtifact> prior = artifactsSnapshot();
        for (int i = prior.size() - 1; i >= 0; i--) {
            GeneratedArtifact old = prior.get(i);
            if (!java.util.Objects.equals(old.path, artifact.path)) continue;
            // A final assistant link is only a reference; it cannot relabel proven output.
            if (GeneratedArtifact.REFERENCE.equals(artifact.kind)
                    && java.util.Objects.equals(old.taskId, artifact.taskId)
                    && !GeneratedArtifact.REFERENCE.equals(old.kind)) return null;
            artifact = artifact.copy();
            artifact.updatedAt = Math.max(artifact.updatedAt, old.updatedAt + 1);
            artifact.history = old.history == null ? new ArrayList<GeneratedArtifact.Version>() : copyVersions(old.history);
            if (old.snapshotPath != null && !old.snapshotPath.trim().isEmpty()) {
                GeneratedArtifact.Version previous = new GeneratedArtifact.Version();
                previous.snapshotPath = old.snapshotPath;
                previous.sourceTool = old.sourceTool;
                previous.revision = old.revision;
                previous.updatedAt = old.updatedAt;
                previous.size = old.size;
                artifact.history.add(previous);
                while (artifact.history.size() > 20) artifact.history.remove(0);
            }
        }
        return artifact;
    }

    /** 登记表提交（锁内、无 IO）：同 path 旧条目的历史已并入 staged，整体替换；
     *  调用方持有 dispatch fence，锁外快照期间不会有同 path 竞争，语义与原单次登记一致。 */
    private synchronized GeneratedArtifact commitArtifact(GeneratedArtifact staged) {
        List<GeneratedArtifact> next = artifactsSnapshot();
        for (int i = next.size() - 1; i >= 0; i--) {
            if (java.util.Objects.equals(next.get(i).path, staged.path)) next.remove(i);
        }
        next.add(staged.copy());
        while (next.size() > 100) next.remove(0);
        artifacts = next;
        return staged.copy();
    }

    private static List<GeneratedArtifact.Version> copyVersions(List<GeneratedArtifact.Version> versions) {
        List<GeneratedArtifact.Version> out = new ArrayList<GeneratedArtifact.Version>();
        if (versions != null) for (GeneratedArtifact.Version version : versions)
            if (version != null) out.add(version.copy());
        return out;
    }

    /** Copies the registered deliverable now, so the next overwrite cannot destroy its prior revision. */
    private String snapshotArtifact(GeneratedArtifact artifact) {
        if (artifact == null || !GeneratedArtifact.DELIVERABLE.equals(artifact.kind)
                || artifact.path == null || workDir == null) return null;
        try {
            java.nio.file.Path source = java.nio.file.Paths.get(artifact.path).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(source) || java.nio.file.Files.size(source) > 64L * 1024 * 1024) return null;
            java.nio.file.Path root = java.nio.file.Paths.get(workDir).toAbsolutePath().normalize()
                    .resolve(".minion").resolve("artifact-history")
                    .resolve(id == null ? "session" : id.replaceAll("[^A-Za-z0-9._-]", "_"));
            java.nio.file.Files.createDirectories(root);
            String name = source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
            long revision = artifact.revision == null ? artifact.updatedAt : artifact.revision;
            String pathKey = Integer.toHexString(artifact.path.hashCode());
            java.nio.file.Path target = root.resolve(revision + "-" + artifact.updatedAt + "-" + pathKey + "-" + name);
            java.nio.file.Path temp = root.resolve(target.getFileName() + ".tmp");
            java.nio.file.Files.copy(source, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (Exception unavailable) {
            return null;
        }
    }
    /** Highest immutable event included in the atomic JSON checkpoint. */
    public long journalSequence;
    public TodoList todos = new TodoList();
    public TaskState taskState = new TaskState();
    /** §1.1 会话成本快照（CostTracker.persistJson 往返；null=无统计） */
    public String costJson;
    /** 会话级读状态（v2.4 §4.5）：Read 登记 / Edit-Write 消费 / 压缩回退失效 ledger。transient：纯内存簿记 */
    public transient volatile com.minion.core.security.ReadStateStore readState = new com.minion.core.security.ReadStateStore();
    public List<QueuedTask> queuedTasks = new ArrayList<QueuedTask>();
    public static class QueuedTask {
        public String id=java.util.UUID.randomUUID().toString();
        public long revision=1;
        public String text;
        public long createdAt;
        public boolean guidance;
        /** §2.3 排队优先级：now（中断在途+队首）/next（队首）/later（队尾，默认） */
        public String priority = "later";
        public List<ImagePart> images = new ArrayList<ImagePart>();
    }
    public UsageTracker usage = new UsageTracker();

    /** 运行中用户补充的挂起队列（尚未入 messages 历史；检查点/下次发送时批量注入后清空）。
     *  随会话落盘：应用退出时未注入的补充不丢。初始化器保证旧文件反序列化后非 null */
    public List<String> pendingSupplements = new ArrayList<String>();

    /** 挂起补充的图片列表（与 pendingSupplements 按索引一一对应；无图 = 空列表）。
     *  随会话落盘：应用退出时未注入的补充图片不丢 */
    public List<List<ImagePart>> pendingSupplementImages = new ArrayList<List<ImagePart>>();

    private static final AtomicLong SEQ = new AtomicLong();

    /** 会话 id 生成：毫秒 + 自增序号。同毫秒内多次生成也唯一且递增（SessionStore 按字符串排序，
     *  需要字典序=时间序且 id 不重复，秒级时间戳在测试中同秒碰撞会导致文件互相覆盖）。
     *  create 与 regenerateId 共用同一机制，保证 id 格式始终一致。 */
    private static String generateId() {
        long seq = SEQ.incrementAndGet() % 10000L;
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                + "-" + String.format("%04d", seq);
    }

    public static Session create(String workDir, String modelName) {
        Session s = new Session();
        s.id = generateId();
        s.createdAt = s.id;
        s.workDir = workDir;
        s.modelName = modelName;
        s.context150kMigrationVersion = 1;
        return s;
    }

    /** /new 用：重新生成会话 id/createdAt（机制同 create）。旧 id 会话已随 /new 落盘，
     *  新会话若沿用旧 id，后续自动落盘会覆盖上一个会话文件 */
    public void regenerateId() {
        id = generateId();
        createdAt = id;
    }

    /** 恢复时用（Task 18） */
    public static Session resume(String id, String createdAt, String workDir,
                                 String modelName, String title, List<Message> messages) {
        Session s = new Session();
        s.id = id;
        s.createdAt = createdAt;
        s.workDir = workDir;
        s.modelName = modelName;
        s.title = title;
        s.messages = messages;
        return s;
    }

    /** 历史会话列表展示：时间 + 最后用户消息摘要 */
    public String preview() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            // M-9：技能消息（pinned）不作为会话预览——轮内注入点在用户输入之后，否则污染预览
            if (m.pinned || m.toolImage) continue;
            if (m.role == Message.Role.USER) {
                String text = m.content == null ? "" : m.content.replace('\n', ' ');
                return text.length() > 50 ? text.substring(0, 50) + "..." : text;
            }
        }
        return "(空会话)";
    }
}
