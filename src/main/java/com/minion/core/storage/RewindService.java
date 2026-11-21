package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.llm.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /rewind 回退服务（开发说明 v2.4 §9.3）：基于 {@link MessageFileHistory} 的消息级回退。
 *
 * <p>模式：<b>code</b>（仅代码，经 planRestore 冲突保护逐文件恢复）、<b>conversation</b>
 * （仅对话：内存截断到目标消息，不物理删 transcript/journal——追加 tombstone 由
 * TranscriptJsonl 承担）、<b>both</b>、<b>summarize</b>（压缩到该点，交给调用方执行压缩）。
 * <b>任何恢复前先自动建立"rewind 前"恢复点</b>（对当前跟踪文件再快照一次，可再次回退）。
 * 缺快照的历史点返回不可代码回退标记；冲突项必须由调用方逐项确认，本服务不默认覆盖。
 */
public final class RewindService {

    /** 回退执行结果（供 UI 展示） */
    public static final class Result {
        public boolean ok;
        public String mode;
        public String targetMessageId;
        public int messagesBefore, messagesAfter;
        public int filesRestored, filesUnchanged, filesDeleted;
        public List<MessageFileHistory.RestoreItem> conflicts = new ArrayList<MessageFileHistory.RestoreItem>();
        public List<String> notes = new ArrayList<String>();
    }

    private final MessageFileHistory history;
    private final Path workDir;       // 相对路径解析基准（跟踪文件清单来源）

    public RewindService(MessageFileHistory history, Path workDir) {
        this.history = history;
        this.workDir = workDir;
    }

    /** 目标消息及之后的消息将被回退；messageId 必须存在快照（无快照返回不可代码回退） */
    public Result rewind(Session session, String messageId, String mode, List<Path> currentTracked) throws IOException {
        MessageFileHistory.Snapshot snapshot = history.snapshot(messageId);
        if (snapshot == null) {
            Result r = new Result();
            r.notes.add("该消息点没有文件快照（checkpoint.messageLevel 关闭或已淘汰），不可代码回退；可用 conversation/summarize 模式");
            if ("code".equals(mode) || "both".equals(mode)) { r.notes.add("已拒绝执行 code/both"); return r; }
        }
        Result result = new Result();
        result.mode = mode;
        result.targetMessageId = messageId;
        result.messagesBefore = session.messages == null ? 0 : session.messages.size();

        // 1) rewind 前恢复点：对当前跟踪文件再快照（可再次回退）
        if (currentTracked != null && !currentTracked.isEmpty()) {
            String guardId = "rewind-guard-" + UUID.randomUUID();
            history.track(guardId, currentTracked);
            result.notes.add("已建立 rewind 前恢复点 " + guardId + "（可再次 /rewind 回到回退前）");
        }

        // 2) code/both：经冲突保护的逐文件恢复
        if (("code".equals(mode) || "both".equals(mode)) && snapshot != null) {
            List<MessageFileHistory.RestoreItem> plan = history.planRestore(messageId);
            if (plan == null) {
                result.notes.add("快照缺失或已损坏");
            } else {
                for (MessageFileHistory.RestoreItem item : plan) {
                    if ("restore".equals(item.status)) {
                        // 冲突（当前哈希 ≠ 记录）必须调用方确认——Result.conflicts 携带，
                        // 本服务默认不覆盖：只有显式带确认集合才写盘（restoreConfirmed）
                        result.conflicts.add(item);
                    } else if ("unchanged".equals(item.status)) {
                        result.filesUnchanged++;
                    } else if ("delete-on-restore".equals(item.status)) {
                        result.conflicts.add(item);   // 删除也需确认（可能含后续合法修改）
                    }
                }
                if (result.conflicts.isEmpty()) {
                    applyPlan(plan);
                    result.filesRestored = countRestored(plan);
                    result.filesDeleted = countDeleted(plan);
                } else {
                    result.notes.add("存在 " + result.conflicts.size() + " 个冲突文件，未写盘；确认后重试（restoreConfirmed）");
                }
            }
        }

        // 3) conversation/both：内存截断到目标消息（含该消息），transcript 不物理删
        if ("conversation".equals(mode) || "both".equals(mode)) {
            int index = -1;
            if (session.messages != null)
                for (int i = session.messages.size() - 1; i >= 0; i--) {
                    Message m = session.messages.get(i);
                    if (messageId.equals(m.eventId)) { index = i; break; }
                }
            if (index >= 0) {
                // 目标消息之后截断（保留目标消息本身：回到"该消息已发送"状态）
                session.messages = new ArrayList<Message>(session.messages.subList(0, index + 1));
                result.messagesAfter = session.messages.size();
                // §4.5：截断淘汰 Read 回执 → "正文仍在上下文"账本失效，重读必须回正文
                if (session.readState != null) session.readState.contextLedger().invalidateAll();
                result.notes.add("对话已回退到目标消息（transcript/journal 保留完整审计链，未物理删除）");
            } else {
                result.notes.add("会话内未找到目标消息 eventId，对话未截断");
            }
        }

        result.ok = true;
        return result;
    }

    /** 冲突确认后的写盘执行（item 集合须来自 planRestore 且经用户逐项确认） */
    public void restoreConfirmed(List<MessageFileHistory.RestoreItem> confirmed) throws IOException {
        applyPlan(confirmed);
    }

    private void applyPlan(List<MessageFileHistory.RestoreItem> plan) throws IOException {
        for (MessageFileHistory.RestoreItem item : plan) history.restoreFile(item);
    }

    private static int countRestored(List<MessageFileHistory.RestoreItem> plan) {
        int n = 0;
        for (MessageFileHistory.RestoreItem i : plan) if ("restore".equals(i.status)) n++;
        return n;
    }

    private static int countDeleted(List<MessageFileHistory.RestoreItem> plan) {
        int n = 0;
        for (MessageFileHistory.RestoreItem i : plan) if ("delete-on-restore".equals(i.status)) n++;
        return n;
    }

    /** 相对跟踪路径按工作区解析 */
    public Path resolve(String path) {
        Path p = Paths.get(path);
        return p.isAbsolute() ? p : workDir.resolve(p);
    }
}
