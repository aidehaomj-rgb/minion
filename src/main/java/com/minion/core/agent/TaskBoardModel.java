package com.minion.core.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.4 §10.6/§10.7 任务面板共用可视化模型（只读快照）：TodoWrite 待办、TaskV2 子任务
 * （状态/负责人/依赖/验收）与后台任务（shell/agent/monitor；实时输出尾部）合并为统一行。
 * 面板不得直接修改任务账本——所有变更经 TaskV2/TodoWrite/TaskStop 工具与串行事务提交，
 * 本模型只做展示投影。/tasks 文本渲染与 GUI 面板共用。
 */
public final class TaskBoardModel {

    public static final String KIND_TODO = "todo";
    public static final String KIND_TASK = "task";
    public static final String KIND_BACKGROUND = "background";

    /** 单行（三类共用字段；不适用的为 null/空） */
    public static final class BoardRow {
        public final String kind;
        public final String id;
        public final String title;        // todo 内容 / 子任务 assignment / 后台命令描述
        public final String owner;        // 子任务 mode（worker/reviewer 等）
        public final String status;       // todo: 待办/完成；task: Child.status；后台: JobRecord.status
        public final String acceptance;   // 子任务验收（result/stopDecision 摘要）
        public final String recentOutput; // 后台任务输出尾部（≤240 字符，无则空）
        public final Integer exitCode;    // 后台任务退出码（未结束 null）

        BoardRow(String kind, String id, String title, String owner, String status,
                String acceptance, String recentOutput, Integer exitCode) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.owner = owner;
            this.status = status;
            this.acceptance = acceptance;
            this.recentOutput = recentOutput;
            this.exitCode = exitCode;
        }
    }

    /** 只读快照（后台注册表可空=无后台任务段） */
    public static List<BoardRow> snapshot(Session session, RuntimeJobRegistry jobs) {
        List<BoardRow> rows = new ArrayList<BoardRow>();
        if (session == null) return rows;
        // TodoWrite 待办（与 TaskV2 同一可视化面）
        synchronized (session.todos) {
            int index = 1;
            for (TodoList.TodoItem item : session.todos.items)
                rows.add(new BoardRow(KIND_TODO, "todo-" + (index++),
                        item.text, "", item.done ? "完成" : "待办", "", "", null));
        }
        // TaskV2 子任务（状态/负责人/依赖/验收）
        TaskState state = session.taskState;
        if (state != null) synchronized (state) {
            for (TaskState.Child child : state.children) {
                String acceptance = child.result == null || child.result.isEmpty()
                        ? (child.stopDecision == null ? "" : child.stopDecision.reason)
                        : child.result;
                rows.add(new BoardRow(KIND_TASK, child.id,
                        child.assignment == null ? "" : child.assignment,
                        child.mode == null ? "" : child.mode,
                        child.status == null ? "" : child.status,
                        clip(acceptance, 120), "", null));
            }
        }
        // 后台任务（ProcessTool 唯一真源的账本；输出尾部只读）
        if (jobs != null) {
            for (RuntimeJobRegistry.JobRecord job : jobs.list()) {
                String tail = outputTail(job.resultPath);
                rows.add(new BoardRow(KIND_BACKGROUND, job.jobId,
                        job.description == null ? job.kind : job.description,
                        job.kind == null ? "" : job.kind,
                        String.valueOf(job.status), "",
                        tail, job.exitCode));
            }
        }
        return rows;
    }

    /** /tasks 文本渲染（GUI 面板与命令共用口径） */
    public static String render(List<BoardRow> rows) {
        StringBuilder sb = new StringBuilder("任务面板（待办/子任务/后台）");
        boolean anyTodo = false, anyTask = false, anyBg = false;
        for (BoardRow r : rows) {
            if (KIND_TODO.equals(r.kind)) anyTodo = true;
            else if (KIND_TASK.equals(r.kind)) anyTask = true;
            else anyBg = true;
        }
        if (anyTodo) {
            sb.append("\n[待办 TodoWrite]");
            for (BoardRow r : rows)
                if (KIND_TODO.equals(r.kind))
                    sb.append("\n  ").append("完成".equals(r.status) ? "☑" : "☐").append(' ').append(r.title);
        }
        if (anyTask) {
            sb.append("\n[子任务 TaskV2]");
            for (BoardRow r : rows)
                if (KIND_TASK.equals(r.kind))
                    sb.append(String.format("%n  %s [%s·%s] %s%s", r.id, r.status, r.owner,
                            clip(r.title, 80),
                            r.acceptance.isEmpty() ? "" : " → " + r.acceptance));
        }
        if (anyBg) {
            sb.append("\n[后台任务 /tasks]");
            for (BoardRow r : rows)
                if (KIND_BACKGROUND.equals(r.kind))
                    sb.append(String.format("%n  %s [%s]%s %s", r.id, r.status,
                            r.exitCode == null ? "" : " exit=" + r.exitCode,
                            r.recentOutput.isEmpty() ? clip(r.title, 60)
                                    : clip(r.recentOutput, 120)));
        }
        if (!anyTodo && !anyTask && !anyBg) sb.append("\n（空）");
        return sb.toString();
    }

    /** 后台输出尾部（≤240 字符；无文件/读失败=空；只读不改账本） */
    static String outputTail(String resultPath) {
        if (resultPath == null || resultPath.trim().isEmpty()) return "";
        try {
            Path file = Paths.get(resultPath);
            if (!Files.isRegularFile(file)) return "";
            long size = Files.size(file);
            long skip = Math.max(0, size - 2048);   // 只读尾部 2KB
            java.io.InputStream in = Files.newInputStream(file);
            try {
                long skipped = 0;
                while (skipped < skip) {
                    long n = in.skip(skip - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                byte[] buf = new byte[2048];
                int read = 0, n;
                while (read < buf.length && (n = in.read(buf, read, buf.length - read)) != -1)
                    read += n;
                String text = new String(buf, 0, read, StandardCharsets.UTF_8).trim();
                // 保留末端（最新输出）：超长取最后 240 字符
                if (text.length() <= 240) return text.replace('\n', ' ').replace('\r', ' ');
                return "…" + text.substring(text.length() - 240)
                        .replace('\n', ' ').replace('\r', ' ');
            } finally {
                in.close();
            }
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private TaskBoardModel() { }
}
