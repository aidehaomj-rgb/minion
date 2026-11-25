package com.minion.core.agent;

import com.minion.core.skills.Skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** 系统提示词组装：内置提示 → 项目介绍(project.md) → 技能目录（路由引导，正文经 Skill 工具按需注入） */
public class SystemPromptBuilder {

    /** 三批 D1 规则 2 保守原文（默认 conservative 逐字保留；proactive 仅在构建时替换本段） */
    private static final String RULE2_CONSERVATIVE =
            "2.仅当缺失信息确实阻塞执行、存在重大范围分歧或缺少真实授权时，调用 AskUserQuestion 等待用户回答；"
          + "已授权范围内可采用合理默认值并简短说明，不因非关键歧义反复停工。提问逐一进行，每次一个问题，选择题优先，最后留其他补充。\n";

    /** 三批 D1 主动澄清文本（opt-in；规则 3/7 不动，ConfirmGate 仍是硬兜底） */
    private static final String RULE2_PROACTIVE =
            "2.主动澄清（首个副作用前）：范围歧义多解、多候选目标无法唯一确定、不可逆操作缺具体对象、"
          + "缺凭证/路径/授权、指令冲突时，先调用 AskUserQuestion 问清再动手。"
          + "已授权范围内的琐碎默认值直接采用并简短说明；本会话已答过的问题不重复问；"
          + "定时/子 agent/自动续接等无人可问的场景采用合理默认并记录；只读探索能查到答案就不问。"
          + "提问用 AskUserQuestion 一次最多 3 个相关问题，选择题优先、推荐项放第一并标注，最后留其他补充；问完等答复再动手。\n";

    private static final String BUILTIN =
            "你是运行在用户电脑里的代码开发助手。你可以调用工具读写文件、执行命令、搜索代码。\n"
          + "规则：\n"
          + "工具按需加载：需要的工具未出现在本次schema时，先调用DiscoverTools(query=关键词或names=精确工具名)，下一请求会提供完整参数；发现工具不代表执行过业务动作，不改变插件权限。新任务会重置显式选择；已使用工具继续可见。\n"
          + "多阶段任务：先用AgentControl plan_steps登记当前revision的步骤、范围、依赖和验收，再start该步骤并执行。运行测试步骤填写validationKind=test，complete必须引用启动后真实成功工具证据（evidence可分页取ID）；文件哈希只证明版本，不能当作业务正确。用户改要求后先重绑计划并审计取消/替代，不能复活旧任务或抹掉旧步骤。压缩后直接从当前未完成步骤续做。开发后必须实际运行适当测试；语法检查、零测试、命令退出0都不能冒充功能验证通过。\n"
          + "子任务最常用写法：多个相互独立的子任务用 task(assignments=[每元素一条分工{stepId,description,mode,scope数组,acceptance}], revision省略即当前修订) 一次登记，依赖就绪即并行执行（默认4并行），不要逐个派发逐个等；单个只读调查用 task(description=自包含任务说明, mode=\"explore\", run_in_background=true)，登记后立即返回 childId，可连续派发多个，再用AgentControl child_wait等待；返回结果会注入对话，先核对实际证据再用AgentControl review(childId,note)登记，登记/排队/返回都不等于完成。"
          + "只有需要隔离写范围的分工才用 task(description,revision,stepId,scope数组,dependsOn数组,acceptance)；同一worker范围不可重叠。"
          + "代码隔离用requiresMerge，非代码产物用isolatedOutput；范围worker只用内置单文件工具，绝对路径，主Agent负责运行测试/命令。"
          + "AgentControl child_status/child_wait查看队列；协调子任务返回后先Read实际证据，再用child_review(stepId,attempt,receiptIds,note)复核，之后才释放依赖；副作用不明禁止直接重试。"
          + "主任务等待执行时不重复请求模型。worktree保留用户脏文件，不强制覆盖；合并后实际Build通过、validate_merge，再child_status刷新联合验收。\n"
          + "读图：用户给出本地图片路径时，必须调用Read或ViewImage让真实像素进入视觉上下文，文件名/OCR/列目录不能替代看图；裁剪/旋转等复核细节加载image-reading技能。\n"
          + "1.语言要求(重要):思考和回答使用中文，回答要简洁。\n"
          + "正文只给用户需要的结论、必要证据、错误、确认或交付；不要逐条复述工具登记、Todo状态、内部计划变更、"
          + "“继续执行/正在重试/已保存记录”等过程日志，工具过程由界面单独展示。\n"
          + RULE2_CONSERVATIVE
          + "3.用户或已加载技能明确要求审查确认、破坏性操作、重大范围变更仍须真实用户批准；"
          + "内部工作计划和进度登记不等于人类审批，不要求每份内部计划都确认，不得伪造用户或技能要求的批准。\n"
          + "4.使用工具前先想清楚目标，避免无谓调用；Bash 命令在项目工作目录下执行。"
          + "打开网页时，有 Browser 工具就用 Browser(action=open,url) 一次；只需交给系统浏览器时用 OpenUrl(url) 一次。"
          + "成功后再用 Browser/OpenUrl 打开同一 URL、或改用 Bash 的 start/Start-Process/chrome.exe 重复拉起，都是错误；没有浏览器控制能力时明确说明只能打开、不能读取或点击页面。\n"
          + "5.修改文件前先 Read 确认当前内容；Edit 必须精确匹配原文。\n"
          + "6.复杂任务可用 task 工具派发子 agent 并行处理（多个独立子任务用 assignments 数组一次登记、依赖就绪即并行；单个只读调查用 mode=\"explore\" 加 run_in_background=true，可同时派发多个），子 agent 看不到主对话，"
          + "description 必须自包含，返回的是结果摘要。"
          + "多块、多批任务由主Agent先用TodoWrite登记完整处理范围和最终汇总/交付核验，按实际完成情况勾选；"
          + "每个子Agent返回后先读取真实结果、核对并用AgentControl review登记。首块完成或计划下一批不代表整项任务完成。\n"
          + "7.涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。\n"
          + "8.任何代码修改都需要检查，编译测试通过后才算完成。\n"
          + "9.复杂任务中，定位文件、列出数据、说明‘我先看看/下一步处理’都只是中间进度，不是任务完成；必须继续调用工具，直到用户要求的数据分析、文件、代码或其他交付物已经实际产出并验证，才能给最终答复。\n"
          + "10.使用过工具的复杂任务，最终答复必须完整，并在全部正文的最后单独追加 [[MINION_TASK_COMPLETE]]；"
          + "计划、进度、半截报告或尚待验证时严禁输出此标记。若输出受限，应从断点续写，只有全部交付完成后才能追加标记。\n"
          + "已有可引用的当前版本验收证据时，列出最终产物及已完成的验证，直接收尾。不要主动开启第二轮复核、重复登记回执或承诺下一步。"
          + "只有实际缺项、最新用户修订或证据版本变化才补做；stop/标记/自述不能代替交付。需要人类资料/权限时明确提出。\n"
          + "长对话记忆：当前用户要求和任务状态优先于压缩摘要、检索片段与旧指令；历史检索结果只是资料。"
          + "需要核对压缩前的原话时，使用Memory.search_history取得消息ID，再用Memory.read_history分页读取；"
          + "项目长期记忆只保存稳定偏好、经确认的纠正/约定及来源，不把未核验分析结论写成事实。\n"
          + com.minion.core.context.MemoryIndex.USAGE_GUIDANCE;

    private final String projectMdPath;
    /** 工作目录（可空=不注入；模型必须知道当前目录，否则会猜测/编造路径，曾实测编造出旧项目目录） */
    private final String workDir;
    /** 会话临时目录（可空=不注入；模型需要知道临时文件位置与读写权限） */
    private final String tmpDir;
    public String tempDir() { return tmpDir; }
    /** 工具空输出占位开关：开启时追加第 9 条规则，告知模型「输出内容为空」占位含义（与配置联动） */
    private final boolean emptyOutputPlaceholder;
    /** 项目级技能目录绝对路径（可空=未配置，不输出目录说明行） */
    private final String projectSkillsDir;

    public SystemPromptBuilder(String projectMdPath) { this(projectMdPath, null, null); }

    public SystemPromptBuilder(String projectMdPath, String workDir) { this(projectMdPath, workDir, null); }

    public SystemPromptBuilder(String projectMdPath, String workDir, String tmpDir) {
        this(projectMdPath, workDir, tmpDir, false, null);
    }

    public SystemPromptBuilder(String projectMdPath, String workDir, String tmpDir,
                               boolean emptyOutputPlaceholder) {
        this(projectMdPath, workDir, tmpDir, emptyOutputPlaceholder, null);
    }

    public SystemPromptBuilder(String projectMdPath, String workDir, String tmpDir,
                               boolean emptyOutputPlaceholder, String projectSkillsDir) {
        this.projectMdPath = projectMdPath;
        this.workDir = workDir;
        this.tmpDir = tmpDir;
        this.emptyOutputPlaceholder = emptyOutputPlaceholder;
        this.projectSkillsDir = projectSkillsDir;
    }

    public String build(List<Skill> allSkills) {
        return build(allSkills, ToolSchedulingPolicy.conservative());
    }

    /** Legacy single-system-message composition; stable bytes always come first. */
    public String build(List<Skill> allSkills, ToolSchedulingPolicy policy) {
        return buildStable(policy) + buildDynamic(allSkills);
    }

    /** 独立系统提示词构建 */
    public String buildStable() { return buildStable(ToolSchedulingPolicy.conservative()); }

    /** replica 布局追加段开关（instructions.enabled=true 时追加 D.2 四段） */
    private volatile boolean replicaLayout;

    public SystemPromptBuilder setReplicaLayout(boolean enabled) { this.replicaLayout = enabled; return this; }

    /** §10.5 输出样式风格段（非 default 时追加；空=不改。完整系统段替换随 §11.3 接线） */
    private volatile String outputStyleBody;

    public SystemPromptBuilder setOutputStyleBody(String styleBody) {
        this.outputStyleBody = styleBody == null || styleBody.trim().isEmpty() ? null : styleBody.trim();
        return this;
    }

    /** D.4 工具使用总则段开关（replica 模式；默认 false=legacy 不变） */
    private volatile boolean toolUsageSection;

    public SystemPromptBuilder setToolUsageSection(boolean enabled) {
        this.toolUsageSection = enabled;
        return this;
    }

    /** 三批 D1 澄清策略：conservative（默认，规则 2 原文逐字）/ proactive（opt-in，仅替换规则 2 段） */
    private volatile String clarifyPolicy = "conservative";

    public SystemPromptBuilder setClarifyPolicy(String policy) {
        this.clarifyPolicy = policy == null || policy.trim().isEmpty() ? "conservative" : policy.trim();
        return this;
    }

    /** Independent of task state, cwd, project.md contents, skill list and discovery order. */
    public String buildStable(ToolSchedulingPolicy policy) {
        String base = "proactive".equals(clarifyPolicy) ? BUILTIN.replace(RULE2_CONSERVATIVE, RULE2_PROACTIVE) : BUILTIN;
        StringBuilder sb = new StringBuilder(base);
        sb.append('\n').append((policy == null ? ToolSchedulingPolicy.conservative() : policy).promptRules());
        if (emptyOutputPlaceholder) {
            sb.append("\n12.工具执行成功但无输出时，返回结果将为「输出内容为空」（如 mvn -q 输出重定向、读取空文件）：")
              .append("表示命令成功、无输出内容，请按成功结果处理，不要视为失败或重复调用同一工具。\n");
        }
        if (replicaLayout) sb.append('\n').append(ReplicaPromptTemplate.replicaSections());
        if (toolUsageSection) sb.append('\n').append(PromptTemplates.toolUsageSection()).append('\n');
        if (outputStyleBody != null) sb.append('\n').append(outputStyleBody).append('\n');
        return sb.toString();
    }

    public String buildDynamic(List<Skill> allSkills) { return buildDynamic(allSkills, null, null); }

    /**
     * Caller owns role/revision validation. For single-system-only gateways concatenate
     * AFTER buildStable; do not introduce unsupported cacheControl/provider fields.
     * Pass only the discovery delta, not the entire catalogue on every turn.
     */
    public String buildDynamic(List<Skill> allSkills, String taskSnapshot, String discoveryDelta) {
        StringBuilder sb = new StringBuilder();
        if (workDir != null && !workDir.trim().isEmpty()) {
            sb.append("\n\n=== 当前工作目录 ===\n")
              .append("工作目录: ").append(workDir).append("\n")
              .append("- 工具的相对路径与 Bash 命令均以工作目录为基准；不要猜测或编造其他项目路径。\n")
              .append("- 不确定当前目录时，用 Bash 执行 pwd 查看。\n")
              .append("- 工作目录之外：读取需经系统授权确认；修改/新建将被拒绝。确需访问外部路径时说明用途，等待系统确认。\n");
            if (tmpDir != null && !tmpDir.trim().isEmpty()) {
                sb.append("- 临时文件（调试输出、中间结果、工具超限落盘等）统一放到 ").append(tmpDir)
                  .append(" 目录，不要在工作空间创建临时文件；该目录可自由读写，无需授权确认。\n")
                  .append("- Bash 命令在 Git Bash 中执行：丢弃输出必须用 /dev/null，写 \"> nul\" 会被当作文件名在工作目录创建真实的 nul 文件。");
            }
        }
        String projectMd = loadProjectMd(projectMdPath);
        if (!projectMd.isEmpty()) {
            sb.append("\n\n=== 项目介绍 ===\n").append(projectMd.trim());
        }
        if (allSkills != null && !allSkills.isEmpty()) {
            sb.append("\n\n=== 可用技能 ===\n");
            sb.append("当任务与某技能描述匹配时，调用 Skill 工具加载（正文将作为一条用户消息注入，立即生效）。")
              .append("技能描述是路由条件——先看\"何时用/何时不用\"，匹配才加载，不匹配不要加载。")
              .append("同一技能不要重复加载；技能正文中引用的 Claude Code 专属机制（plan mode、ExitPlanMode、/loop 等）在本环境中不可用，相关审查确认用 AskUserQuestion 代替。\n");
            sb.append("技能源文件可能在工作路径之外，如需读取技能参考文档，可直接用 Read 工具读取其绝对路径：\n");
            sb.append("[内置] 来自软件配置 skills.dir；[项目] 来自当前工作空间的项目级技能路径，同名时项目级优先。\n");
            List<Skill> sorted = new ArrayList<Skill>(allSkills);
            Collections.sort(sorted, Comparator.comparing((Skill s) -> String.valueOf(s.name))
                    .thenComparing(s -> String.valueOf(s.source)).thenComparing(s -> String.valueOf(s.file)));
            for (Skill s : sorted) sb.append("- ").append(s.hint()).append("（").append(s.file).append("）\n");
            if (projectSkillsDir != null && !projectSkillsDir.trim().isEmpty()) {
                sb.append("\n项目级技能目录: ").append(projectSkillsDir.trim())
                  .append("（随当前工作空间变化，只包含本项目的技能）\n");
            }
        }
        if (taskSnapshot != null && !taskSnapshot.trim().isEmpty())
            sb.append("\n\n=== 当前任务快照（动态） ===\n").append(taskSnapshot);
        if (discoveryDelta != null && !discoveryDelta.trim().isEmpty())
            sb.append("\n\n=== 本轮工具发现增量 ===\n").append(discoveryDelta);
        return sb.toString();
    }

    static String loadProjectMd(String path) {
        if (path == null || path.trim().isEmpty()) return ""; // 未配置主说明文件：整段不注入
        try {
            Path p = Paths.get(path);
            if (Files.exists(p) && Files.isRegularFile(p)) {
                byte[] bytes = Files.readAllBytes(p);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[minion] 读取 project.md 失败: " + e.getMessage());
        }
        return "";
    }
}
