package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 派发子 agent。由 AgentLoop 提供执行器。
 *
 * <p>2026-11-23 内网弱模型（qwen flash）实测：主 agent 一个子 agent 都没派出去，全部由自己做。
 * 原因是入口对模型不友好——参数没有任何说明、工具描述“推荐”的结构化入口要 6 个必填键且格式严格、
 * 报错又太简略没法照着改。本类的职责因此是：
 * <ul>
 * <li>schema 给每个参数写清楚用途与格式，描述里把最常用的用法（只读调查 + 后台并行）放在最前；</li>
 * <li>常见变体自动纠正而不是拒绝：mode 同义词、任务说明写在 prompt/task 等字段里、
 *     scope/dependsOn 传成字符串、stepId 含中文或空格；</li>
 * <li>结构化入口自愈：能安全补默认值的就补（revision 省略=当前修订、dependsOn 省略=无依赖、
 *     只读任务 scope 省略=整个工作区），只有真正关系到安全的缺项才拒绝，且提示要能照着改
 *     （写入型任务必须给出 scope）。</li>
 * </ul>
 */
public class TaskTool implements Tool {

    /** 仅登记到独立调度器并立即返回；禁止在主工具池同步等待子任务。 */
    public interface Dispatcher { ToolResult submit(JsonObject args);
        /** 批量登记（assignments 数组）；默认不支持，由协调器覆写。 */
        default ToolResult submitBatch(JsonObject args){return ToolResult.errorWithoutSideEffects("调度器不支持批量登记");} }
    private final Dispatcher dispatcher;
    private final AgentLoop legacyLoop;

    public TaskTool(AgentLoop loop) { this.dispatcher = null; this.legacyLoop=loop; }
    public TaskTool(Dispatcher dispatcher) { this.dispatcher = java.util.Objects.requireNonNull(dispatcher); this.legacyLoop=null; }

    @Override public String name() { return "task"; }

    @Override public String description() {
        return "派发子 agent（继承主模型，禁止传 model）。子 agent 看不到主对话，所以 description 必须自包含："
                + "目标、要读的文件的绝对路径、要做什么、返回格式。"
                + "最常用：只读调查传 mode=\"explore\"；要多个子 agent 并行就再加 run_in_background=true——"
                + "登记后立即返回 childId，可连续派发多个，然后调用 AgentControl 的 child_wait 等待；"
                + "子 agent 返回后结果会出现在对话和任务状态里，用 AgentControl review(childId,note) 复核。"
                + "只有需要隔离写范围的分工才用 stepId/scope/dependsOn/acceptance 进入协调队列。"
                + "要一次派多个独立子任务就用 assignments 数组（每个元素一条分工：stepId/description/mode/scope/dependsOn/acceptance），"
                + "登记后依赖就绪即并行执行，不必逐个派发逐个等；顶层 revision 省略即当前修订。"
                + "排队和已登记都不算完成，不能把排队当结果。";
    }

    @Override public JsonObject schema() {
        boolean structured = dispatcher != null;
        JsonObject schema = SchemaGenerator.objectSchema("派发子 agent",
                new String[]{"description", "prompt", "mode", "allowedTools", "subagent_type", "run_in_background",
                        "isolatedOutput", "stepId", "scope", "dependsOn", "acceptance", "timeoutMillis",
                        "requiresMerge", "revision", "assignments"},
                structured
                        ? new String[]{"description", "stepId", "scope", "dependsOn", "acceptance"}
                        : new String[]{"description"});
        JsonObject props = schema.getAsJsonObject("properties");
        describe(props, "description",
                "必填。交给子 agent 的完整任务说明：目标、相关文件绝对路径、要做什么、返回格式。"
                        + "子 agent 看不到主对话，这里写什么它就知道什么。");
        describe(props, "prompt", "可选。补充要求或返回格式，会追加在 description 之后。");
        describe(props, "mode", "可选，默认 worker。worker=可读写；explore=只读调查（不改文件）；plan=只读规划。");
        describe(props, "allowedTools", "可选。逗号分隔的工具名白名单，限制子 agent 可用的工具。");
        describe(props, "subagent_type", "可选。general-purpose/Explore/Plan，或 .minion/agents/*.md 里定义的名称。");
        typed(props, "run_in_background", "boolean",
                "可选。true=后台执行：立即返回 childId，可继续派发其他子 agent，之后用 AgentControl child_wait 等待；"
                        + "省略或 false=同步执行，等子 agent 返回才继续。");
        typed(props, "isolatedOutput", "boolean", "可选。true=在独立输出目录执行，不与主任务混写。");
        describe(props, "stepId", "仅结构化用法需要。本步的唯一标识：英文、数字、下划线、点、短横线，如 extract-1。");
        array(props, "scope", "仅结构化用法需要。允许写入的相对路径前缀数组，如 [\"src/\"]；只读任务可填 [\".\"]。");
        array(props, "dependsOn", "仅结构化用法需要。依赖的前置 stepId 数组；没有依赖填 []。");
        describe(props, "acceptance", "仅结构化用法需要。验收条件：子 agent 必须交付什么、用什么证据证明。");
        typed(props, "timeoutMillis", "integer", "可选。子任务最长执行时间（毫秒），1000 到 86400000，默认 600000。");
        typed(props, "requiresMerge", "boolean", "可选。true=在独立 worktree 执行，返回后需要合并验证。");
        typed(props, "revision", "integer", "仅结构化用法需要。当前任务修订号，即任务状态里“指令修订: N”的 N。");
        JsonObject batchProp = new JsonObject();
        batchProp.addProperty("type", "array");
        batchProp.addProperty("description", "可选。批量派发：一次登记多条分工，依赖就绪即并行执行，不必逐个派发逐个等。"
                + "每个元素是一条分工对象；用 assignments 时顶层只读 assignments 与 revision（省略即当前修订），"
                + "顶层 stepId/scope 等单条键可不填。整批原子：任一条非法整批不登记。");
        JsonObject item = SchemaGenerator.objectSchema("一条子任务分工",
                new String[]{"stepId", "description", "mode", "scope", "dependsOn", "acceptance"},
                new String[]{"stepId", "description", "acceptance"});
        JsonObject itemProps = item.getAsJsonObject("properties");
        describe(itemProps, "stepId", "本步唯一标识：英文、数字、下划线、点、短横线，如 extract-1；批内不能重复。");
        describe(itemProps, "description", "交给子 agent 的完整任务说明：目标、相关文件绝对路径、要做什么、返回格式。");
        describe(itemProps, "mode", "worker=可写（必须给 scope）；explore=只读调查。省略默认 worker。");
        array(itemProps, "scope", "允许写入的相对路径前缀数组，如 [\"src/\"]；只读任务可填 [\".\"]。");
        array(itemProps, "dependsOn", "依赖的前置 stepId 数组；没有依赖填 [] 或省略。");
        describe(itemProps, "acceptance", "验收条件：子 agent 必须交付什么、用什么证据证明。");
        batchProp.add("items", item);
        props.add("assignments", batchProp);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @Override public ToolResult execute(JsonObject args) {
        if (args==null) return ToolResult.errorWithoutSideEffects(
                "缺少任务参数：至少提供 description（交给子 agent 的完整任务说明）");
        if (args.has("model") && !args.get("model").isJsonNull())
            return ToolResult.error("子Agent模型已锁定为主会话模型，不能单独指定model");
        if (args.has("assignments") && !args.get("assignments").isJsonNull()) {
            if (!args.get("assignments").isJsonArray())
                return ToolResult.errorWithoutSideEffects("assignments 必须是数组：每个元素一条子任务分工对象");
            JsonArray raw = args.getAsJsonArray("assignments");
            if (raw.size() == 0 || raw.size() > 64)
                return ToolResult.errorWithoutSideEffects("assignments 须为 1..64 条分工，收到 " + raw.size());
            JsonObject call = args.deepCopy();
            if (dispatcher == null && legacyLoop != null) {
                // 与单条结构化入口同一套自愈：mode 同义词、scope 字符串包数组、stepId 规范化、可补默认值就补
                if (text(call, "revision").trim().isEmpty())
                    call.addProperty("revision", legacyLoop.session().taskState.revision());
                JsonArray normalized = new JsonArray();
                for (int i = 0; i < raw.size(); i++) {
                    if (!raw.get(i).isJsonObject())
                        return ToolResult.errorWithoutSideEffects("assignments[" + i + "] 必须是对象（含 stepId/description 等分工键）");
                    JsonObject element = normalize(raw.get(i).getAsJsonObject());
                    ToolResult problem = prepareStructured(element);
                    if (problem != null)
                        return ToolResult.errorWithoutSideEffects("assignments[" + i + "] " + problem.output);
                    normalized.add(element);
                }
                call.add("assignments", normalized);
                return legacyLoop.submitCoordinatedChildBatch(call);
            }
            if (dispatcher != null) return dispatcher.submitBatch(call);
            return ToolResult.errorWithoutSideEffects("子任务协调器尚未接线：需宿主注入TaskTool(Dispatcher)；本次未派发");
        }
        if(dispatcher==null&&legacyLoop!=null) {
            JsonObject call = normalize(args);
            String mode = normalizeMode(text(call, "mode"));
            if (!text(call, "mode").isEmpty() && mode == null)
                return ToolResult.errorWithoutSideEffects("mode只支持worker/explore/plan（收到「" + text(call, "mode")
                        + "」）：只读调查用 explore，可写用 worker，只读规划用 plan");
            if (isStructured(call)) {
                ToolResult problem = prepareStructured(call);
                if (problem != null) return problem;
                return legacyLoop.submitCoordinatedChild(call);
            }
            if(text(call,"description").trim().isEmpty())return ToolResult.errorWithoutSideEffects(
                    "缺少description：请在 description 里写完整的任务说明（目标、相关文件绝对路径、要做什么、返回格式）");
            String text=legacyLoop.runSubAgent(call);
            if(text==null||text.startsWith("子Agent失败")||text.contains("【状态】失败/中断")||text.startsWith("子Agent已在运行"))return ToolResult.error(text==null?"子Agent未返回结果":text);
            return ToolResult.success(text);
        }
        for(String key:new String[]{"description","stepId","scope","dependsOn","acceptance"})if(!args.has(key)||args.get(key).isJsonNull())return ToolResult.errorWithoutSideEffects("缺少 "+key+" 参数");
        if(dispatcher==null)return ToolResult.errorWithoutSideEffects("子任务协调器尚未接线：需宿主注入TaskTool(Dispatcher)，旧字符串runner不能确认真实成功；本次未派发");
        try{ToolResult result=dispatcher.submit(args.deepCopy());return result==null?ToolResult.error("子任务调度器未返回回执"):result;}
        catch(Exception e){return ToolResult.error("子任务登记异常，需核对是否已登记，不能盲目重试: "+e.getMessage());}
    }

    // ==================== 参数纠正 ====================

    private static final List<String> READ_ONLY_MODES = Arrays.asList(
            "readonly", "read-only", "read_only", "reader", "read", "research", "search", "investigate",
            "investigator", "analysis", "analyst", "analyze", "explorer", "view", "调查", "检索", "只读", "分析", "查询");
    private static final List<String> WRITE_MODES = Arrays.asList(
            "write", "writer", "implement", "implementation", "coder", "coding", "developer", "dev",
            "default", "general", "general-purpose", "generalpurpose", "执行", "写入", "实现");
    private static final List<String> PLAN_MODES = Arrays.asList("planning", "planner", "规划", "计划");
    /** 模型把任务说明写进这些字段时的兜底（description 为空时取第一个非空的当任务说明） */
    private static final List<String> DESCRIPTION_ALIASES = Arrays.asList("prompt", "task", "instructions", "input", "query");

    /** 是否走了结构化入口（任一结构化参数出现即算） */
    static boolean isStructured(JsonObject args) {
        return args.has("stepId")||args.has("scope")||args.has("dependsOn")||args.has("revision")||args.has("requiresMerge")||args.has("assignments");
    }

    /**
     * 常见变体纠正（不改语义）：任务说明字段兜底、mode 同义词归一、数组参数传成单个字符串时包成数组。
     * mode 无法识别时保持原值，由调用方给出明确提示。
     */
    static JsonObject normalize(JsonObject raw) {
        JsonObject call = raw.deepCopy();
        if (text(call, "description").trim().isEmpty()) {
            for (String alias : DESCRIPTION_ALIASES) {
                String value = text(call, alias);
                if (!value.trim().isEmpty()) {
                    call.addProperty("description", value);
                    call.remove(alias);   // 已用作任务说明，不再重复追加
                    break;
                }
            }
        }
        String mode = normalizeMode(text(call, "mode"));
        if (mode != null) call.addProperty("mode", mode);
        wrapSingleString(call, "scope");
        wrapSingleString(call, "dependsOn");
        return call;
    }

    /** worker/explore/plan 三种合法模式之一；无法识别返回 null */
    static String normalizeMode(String raw) {
        if (raw == null) return null;
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) return null;
        if (Arrays.asList("worker", "explore", "plan").contains(mode)) return mode;
        if (READ_ONLY_MODES.contains(mode)) return "explore";
        if (WRITE_MODES.contains(mode)) return "worker";
        if (PLAN_MODES.contains(mode)) return "plan";
        return null;
    }

    private static void wrapSingleString(JsonObject call, String key) {
        if (!call.has(key) || !call.get(key).isJsonPrimitive()) return;
        JsonArray array = new JsonArray();
        String value = call.get(key).getAsString().trim();
        for (String part : value.split("[,，;；\\s]+")) if (!part.isEmpty()) array.add(part);
        call.add(key, array);
    }

    /**
     * 结构化入口自愈。返回 null=参数已就绪，可以登记；否则返回一条能照着改的拒绝原因。
     * 只补默认值不放宽安全：写入范围（scope）对写入型任务仍然必须由调用方给出。
     */
    private ToolResult prepareStructured(JsonObject call) {
        String normalized = text(call, "mode").isEmpty() ? "worker" : normalizeMode(text(call, "mode"));
        if (normalized == null)
            return ToolResult.errorWithoutSideEffects("mode只支持worker/explore/plan（收到「" + text(call, "mode") + "」）");
        if ("plan".equals(normalized)) normalized = "explore";   // 协调队列只有可写/只读两种执行方式
        call.addProperty("mode", normalized);

        // stepId 只是标签：规范化非法字符；缺省时生成一个
        String stepId = sanitizeStepId(text(call, "stepId"));
        if (stepId == null) stepId = "step-" + Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
        call.addProperty("stepId", stepId);

        // revision 省略即当前修订：这次调用就发生在当前轮，不可能是“把旧分工送入新指令”；
        // 显式写了旧值仍由协调器拒绝
        if (text(call, "revision").trim().isEmpty())
            call.addProperty("revision", legacyLoop.session().taskState.revision());
        if (!call.has("dependsOn") || call.get("dependsOn").isJsonNull()) call.add("dependsOn", new JsonArray());
        if (text(call, "acceptance").trim().isEmpty())
            call.addProperty("acceptance", "子 agent 返回结论、实际执行的动作和证据路径（文件绝对路径或命令结果），未完成项如实列出");

        boolean hasScope = call.has("scope") && call.get("scope").isJsonArray()
                && call.get("scope").getAsJsonArray().size() > 0;
        if (!hasScope) {
            if ("worker".equals(normalized))
                return ToolResult.errorWithoutSideEffects("写入型（mode=worker）子任务必须提供 scope：允许写入的相对路径前缀数组，"
                        + "如 [\"src/\"]；只读任务请传 mode=\"explore\"，scope 可以不填");
            JsonArray whole = new JsonArray();
            whole.add(".");
            call.add("scope", whole);
        }
        if (text(call, "description").trim().isEmpty())
            return ToolResult.errorWithoutSideEffects("缺少description：请在 description 里写交给子 agent 的完整任务说明");
        return null;
    }

    /** 规范化为协调器接受的标识（[A-Za-z0-9_.-]{1,128}）：非法字符换成短横线；规范化后为空返回 null */
    static String sanitizeStepId(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            out.append(plain || c == '_' || c == '.' || c == '-' ? c : '-');
        }
        String id = out.toString();
        if (id.length() > 128) id = id.substring(0, 128);
        return id.isEmpty() ? null : id;
    }

    // ==================== schema 小工具 ====================

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement value = o.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static void describe(JsonObject props, String key, String description) {
        props.getAsJsonObject(key).addProperty("description", description);
    }

    private static void typed(JsonObject props, String key, String type, String description) {
        JsonObject prop = props.getAsJsonObject(key);
        prop.addProperty("type", type);
        if (description != null) prop.addProperty("description", description);
    }

    private static void array(JsonObject props, String key, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "array");
        prop.addProperty("description", description);
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        prop.add("items", item);
        props.add(key, prop);
    }
}
