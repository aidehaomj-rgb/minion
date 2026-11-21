package com.minion.core.team;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队工具（v2.4 §3.5/§8.3，team.enabled 默认 false）：TeamCreate/TeamDelete/SendMessage
 * ——文件邮箱 {@link TeamMailbox} 的工具面。teammate 线程模型（独立 AgentLoop 线程）随 §8.2
 * 协调器模式切片接入；本切片先落邮箱工具与消息收发。
 */
public final class TeamTools {

    /** 邮箱根：{workDir}/.minion/teams */
    public static Path teamsRoot(String workDir) {
        return java.nio.file.Paths.get(workDir, ".minion", "teams");
    }

    private static String membersList(TeamMailbox mailbox) throws IOException {
        return String.join(", ", mailbox.members());
    }

    /** TeamCreate：建团队（成员 inbox 初始化，幂等） */
    public static final class TeamCreateTool implements Tool {
        private final String workDir;
        public TeamCreateTool(String workDir) { this.workDir = workDir; }

        @Override public String name() { return "TeamCreate"; }
        @Override public String description() { return "创建团队文件邮箱（成员含 lead；幂等）"; }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("创建团队",
                    new String[]{"team", "members"}, new String[]{"team", "members"});
        }
        @Override public boolean isHighRisk(JsonObject args) { return true; }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            String team = args.has("team") ? args.get("team").getAsString().trim() : "";
            List<String> members = new ArrayList<String>();
            if (args.has("members") && args.get("members").isJsonArray())
                for (com.google.gson.JsonElement e : args.getAsJsonArray("members"))
                    if (!e.isJsonNull()) members.add(e.getAsString().trim());
            if (!members.contains("lead")) members.add(0, "lead");
            try {
                TeamMailbox mailbox = new TeamMailbox(teamsRoot(workDir), team);
                mailbox.create(members);
                return ToolResult.success("团队已建立: " + team + "（成员: " + membersList(mailbox) + "）");
            } catch (IllegalArgumentException invalid) {
                return ToolResult.errorWithoutSideEffects("团队/成员名非法: " + invalid.getMessage());
            }
        }
    }

    /** TeamDelete：删除团队邮箱目录（不可逆，高危） */
    public static final class TeamDeleteTool implements Tool {
        private final String workDir;
        public TeamDeleteTool(String workDir) { this.workDir = workDir; }

        @Override public String name() { return "TeamDelete"; }
        @Override public String description() { return "删除团队邮箱目录（不可逆）"; }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("删除团队", new String[]{"team"}, new String[]{"team"});
        }
        @Override public boolean isHighRisk(JsonObject args) { return true; }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            String team = args.has("team") ? args.get("team").getAsString().trim() : "";
            new TeamMailbox(teamsRoot(workDir), team).deleteTeam();
            return ToolResult.success("团队邮箱已删除: " + team);
        }
    }

    /** SendMessage：向成员或广播（to=* 除自己）发消息；type 支持 text/shutdown_request 等 */
    public static final class SendMessageTool implements Tool {
        private final String workDir;
        public SendMessageTool(String workDir) { this.workDir = workDir; }

        @Override public String name() { return "SendMessage"; }
        @Override public String description() {
            return "团队邮箱发消息（to=成员名或 * 广播；type: text/shutdown_request/plan_approval_request/response）";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("发送团队消息",
                    new String[]{"team", "from", "to", "text"}, new String[]{"team", "from", "to", "text"});
        }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            String team = args.has("team") ? args.get("team").getAsString().trim() : "";
            String from = args.has("from") ? args.get("from").getAsString().trim() : "lead";
            String to = args.has("to") ? args.get("to").getAsString().trim() : "";
            String type = args.has("type") && !args.get("type").isJsonNull()
                    ? args.get("type").getAsString() : "text";
            String text = args.has("text") ? args.get("text").getAsString() : "";
            if (to.isEmpty() || text.trim().isEmpty())
                return ToolResult.errorWithoutSideEffects("缺少 to/text 参数");
            try {
                TeamMailbox mailbox = new TeamMailbox(teamsRoot(workDir), team);
                int delivered = mailbox.send(from, to, type, text);
                return ToolResult.success("已投递 " + delivered + " 个邮箱（" + from + " → " + to + "）");
            } catch (IllegalArgumentException invalid) {
                return ToolResult.errorWithoutSideEffects("参数非法: " + invalid.getMessage());
            } catch (IOException failed) {
                return ToolResult.errorWithoutSideEffects("投递失败: " + failed.getMessage());
            }
        }
    }

    private TeamTools() { }
}
