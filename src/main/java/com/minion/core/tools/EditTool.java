package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 精确字符串替换。始终为高危操作（修改现有文件）。 */
public class EditTool implements Tool {

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;
    private final CheckpointStore checkpoints;
    private final ConfirmGate confirm;
    /** 会话级读状态（v2.4 §4.5）；null=未接线，跳过校验 */
    private final com.minion.core.security.ReadStateStore readState;
    /** v2.4 §3.1 read.ccSemantics：read-before-write 与整文件哈希校验开关 */
    private final boolean ccSemantics;

    public EditTool(Workspace workspace) { this(workspace, null); }

    public EditTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir) {
        this(workspace, skillsDir, tmpDir, null, null);
    }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir, CheckpointStore checkpoints) {
        this(workspace, skillsDir, tmpDir, checkpoints, null);
    }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this(workspace, skillsDir, tmpDir, null, confirm);
    }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir,
                    CheckpointStore checkpoints, ConfirmGate confirm) {
        this(workspace, skillsDir, tmpDir, checkpoints, confirm, null, false);
    }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir,
                    CheckpointStore checkpoints, ConfirmGate confirm,
                    com.minion.core.security.ReadStateStore readState, boolean ccSemantics) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.checkpoints = checkpoints;
        this.confirm = confirm;
        this.readState = readState;
        this.ccSemantics = ccSemantics;
    }

    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() { return "在文件中做精确字符串替换，需严格匹配原文"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("编辑文件",
                new String[]{"path", "oldString", "newString", "replaceAll"},
                new String[]{"path", "oldString", "newString"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return true; }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("oldString") || !args.has("newString")) {
            return ToolResult.error("缺少 path/oldString/newString 参数");
        }
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        // T8 约定：存在性/目录检查在守卫之前；守卫的 toRealPath 对不存在的路径会误报越界
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workspace, skillsDir, tmpDir, p);
        if (guard != null && (confirm == null || !confirm.checkEscapeWrite(this, args, p.toString()))) {
            return guard;
        }
        // §3.1/§4.5 read-before-write + 整文件哈希冲突校验（read.ccSemantics；null=放行）
        ToolResult readGate = checkReadState(p);
        if (readGate != null) return readGate;

        String oldString = args.get("oldString").getAsString();
        if (oldString.isEmpty()) return ToolResult.error("oldString 不能为空");
        String newString = args.get("newString").getAsString();
        if (oldString.equals(newString)) return ToolResult.error("oldString 与 newString 相同，无变更不写入");
        ReentrantLock lock = PathLocks.forPath(p);
        try {
            if (!lock.tryLock(PathLocks.WAIT_SECONDS, TimeUnit.SECONDS))
                return ToolResult.error("文件正被其他操作占用，稍后重试: " + p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("编辑被取消（等待文件锁时线程中断）: " + p);
        }
        try {
        // 编码探测：UTF-8 严格优先，GBK 文件（记事本 ANSI 保存）降级，写回须保持原编码
        TextFiles.Decoded d;
        try { d = TextFiles.decode(Files.readAllBytes(p)); }
        catch (RuntimeException e) { return ToolResult.error("文件编码已损坏（UTF-8 与 GBK 均无法解码），无法安全编辑；请先用 Read 或 Bash 确认文件状态: " + p); }
        String content = d.text;

        // 行尾归一化匹配：Read 工具按行读取并剥离行尾显示，agent 提供的 oldString 通常为 LF；
        // 而 Windows/Git 检出的文件多为 CRLF，直接精确匹配会"未找到待替换内容"。
        // 因此把文件内容与 oldString/newString 统一归一化为 \n 再匹配，
        // 写回时按原文件行尾风格恢复（LF 文件行为不变）。
        boolean crlf = content.contains("\r\n");
        String matchContent = crlf ? content.replace("\r\n", "\n") : content;
        String matchOld = oldString.replace("\r\n", "\n");
        String matchNew = newString.replace("\r\n", "\n");

        int count = countOccurrences(matchContent, matchOld);
        if (count == 0) {
            return ToolResult.error("未找到待替换内容，请先 Read 确认当前内容。oldString=" + preview(oldString)
                    + nearestMatchHint(matchContent, matchOld));
        }
        boolean replaceAll = args.has("replaceAll") && args.get("replaceAll").getAsBoolean();
        if (count > 1 && !replaceAll) {
            return ToolResult.error("oldString 多处匹配（" + count + " 处），需 replaceAll=true 或提供更精确的 oldString");
        }
        String updated = replaceAll ? matchContent.replace(matchOld, matchNew)
                : matchContent.replaceFirst(java.util.regex.Pattern.quote(matchOld),
                        java.util.regex.Matcher.quoteReplacement(matchNew));
        if (crlf) updated = updated.replace("\n", "\r\n");
        CheckpointStore.Entry checkpoint=null;
        if (checkpoints != null) {
            try { checkpoint=checkpoints.create(p, "Edit"); }
            catch (IOException e) { return ToolResult.error("创建编辑前检查点失败，未修改文件: " + e.getMessage()); }
        }
        // §3.1 写入原子段内二次校验：锁内整文件哈希与读取时一致才落盘（防校验后外部修改）
        if (ccSemantics && readState != null) {
            com.minion.core.security.ReadStateStore.Entry e =
                    readState.get(com.minion.core.security.ReadStateStore.keyOf(p));
            if (e != null && !com.minion.core.security.ReadStateStore.sha256(p).equals(e.contentHash))
                return ToolResult.error("写入前二次校验失败：文件在编辑过程中被外部修改，未写入。请重新 Read: " + p);
        }
        AtomicFiles.writeBytes(p, updated.getBytes(d.charset)); // 按原编码写回，不破坏文件其余内容
        if(checkpoint!=null)checkpoints.seal(checkpoint);
        updateReadState(p);
        return ToolResult.success("已替换 " + (replaceAll ? count : 1) + " 处: " + p);
        } finally {
            lock.unlock();
        }
    }

    /** §3.1/§4.5：read.ccSemantics 时校验已读（含切片读）且整文件哈希未变；null=放行 */
    private ToolResult checkReadState(Path p) throws IOException {
        if (!ccSemantics || readState == null) return null;
        com.minion.core.security.ReadStateStore.Entry e =
                readState.get(com.minion.core.security.ReadStateStore.keyOf(p));
        if (e == null)
            return ToolResult.error("本会话尚未 Read 该文件，拒绝盲改；请先 Read 再 Edit（offset/limit 切片读也算已读）: " + p);
        if (e.isPartialView)
            return ToolResult.error("该文件此前只有自动注入的部分视图，未经显式 Read；请先 Read 再 Edit: " + p);
        if (!com.minion.core.security.ReadStateStore.sha256(p).equals(e.contentHash))
            return ToolResult.error("文件自上次 Read 后已被修改（整文件 SHA-256 不一致），未写入；请重新 Read 合并修改: " + p);
        return null;
    }

    /** 写入成功后回写读状态：时间戳与新整文件哈希，旧正文作废（防下次去重误判） */
    private void updateReadState(Path p) throws IOException {
        if (readState == null) return;
        long mtime;
        try { mtime = Files.getLastModifiedTime(p).toMillis(); } catch (IOException absent) { mtime = 0L; }
        readState.updateAfterWrite(com.minion.core.security.ReadStateStore.keyOf(p),
                com.minion.core.security.ReadStateStore.sha256(p), mtime);
    }

    private static int countOccurrences(String s, String sub) {        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** 匹配失败时定位仅缩进/空白差异的候选，附行号提示省一次全文件 Read；不做模糊替换。 */
    static String nearestMatchHint(String content, String old) {
        String[] fileLines = content.split("\n", -1);
        String[] oldLines = old.split("\n", -1);
        if (fileLines.length > 20000 || oldLines.length > 100) return "";
        String[] target = new String[oldLines.length];
        for (int i = 0; i < oldLines.length; i++) target[i] = oldLines[i].trim();
        int hits = 0;
        int firstStart = -1;
        for (int start = 0; start + oldLines.length <= fileLines.length; start++) {
            boolean match = true;
            for (int i = 0; i < oldLines.length; i++) {
                if (!fileLines[start + i].trim().equals(target[i])) { match = false; break; }
            }
            if (match) {
                hits++;
                if (firstStart < 0) firstStart = start;
            }
        }
        if (hits == 0) return "";
        if (hits > 1) {
            return "\n有 " + hits + " 处仅空白差异的相似内容（首处第 " + (firstStart + 1) + " 行），请提供更精确的 oldString。";
        }
        String sample = fileLines[firstStart];
        if (sample.length() > 120) sample = sample.substring(0, 120) + "...";
        int endLine = firstStart + oldLines.length;
        return "\n第 " + (firstStart + 1) + "-" + endLine + " 行存在仅缩进/空白差异的相似内容：" + sample
                + "\n请按文件实际缩进修正 oldString 后重试。";
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }
}
