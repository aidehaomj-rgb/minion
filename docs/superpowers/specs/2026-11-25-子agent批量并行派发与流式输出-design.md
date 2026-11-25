# 子agent批量并行派发与流式输出锁定（J 批）设计

日期：2026-11-25（HEAD f0231f7，I1/I2 后）
状态：**方案已获用户确认（AskUserQuestion：协调队列默认 4 并行）**。
用户指令："现在子agent怎么每次只分配一个，不能并行处理，参考 minion github 端的做法，
让子agent能并行多个处理，然后每个子agent流式输出。"（连发四次，优先级最高）

## 1. 现状核查（HEAD + 上游 github.com/aacw1/minion main 镜像实测）

### 上游做法（设计参考，不抄代码）
- 模块化 turn 引擎（TurnEngine/ModelTurnRunner/ToolBatch/ToolCallExecutor）：同一轮独立工具调用
  pool.submit 并行执行；task 调用即并行派发。
- SubAgentUi：按子代理编号把 onThinking/onContent/onToolCall/onToolResult 映射为
  【子代理N】流式事件，重试提示每会话一次防刷屏。

### 本地等价管线（已具备，缺入口/上限/引导）
- **并行执行已有**：§8.1 后台池 newCachedThreadPool（run_in_background=true 无限并行）；
  协调队列 ChildTaskCoordinator.tick 独立线程泵（`minion-child-<step>`），capacity 内并行，
  依赖就绪即泵、submitBatch 批量登记**内部已存在**（ChildTaskCoordinator.java:116）。
- **流式已有**：executeChild 统一经 ui.onChildEvent(childId, START/THINKING/CONTENT/TOOL_CALL/
  TOOL_RESULT/DONE) → ChatView 按【子Agent xxxxxxxx】标签折叠行实时渲染；协调型与后台型同管线。
- **缺口①入口**：TaskTool 结构化用法只收单条 assignment（submit→submitBatch(singletonList)），
  模型派 N 个分块只能 N 次串行工具调用；弱模型往往派一个→child_wait→复核→再派下一个=串行观感。
- **缺口②上限**：AgentLoop:4097 硬编码 capacity=2。
- **缺口③引导**：SystemPromptBuilder:36/39/52 与 TaskTool 描述未教"独立分工一次批量派发/
  不必等前一个复核再派下一个"，复核释放依赖的文案被弱模型读成串行许可。
- 办公室旧包另叠加 G1/G2（已修，2.4.8 携带）。

## 2. J1 批量派发入口（additive，单条路径逐字不变）

- `ChildTaskCoordinator`：submit 的解析体抽为私有 `parseAssignment(JsonObject)`；新增
  `public synchronized List<Record> submitBatchArgs(List<JsonObject>)` → 解析后走既有
  submitBatch(List<Assignment>)（校验/去重/scope 重叠/依赖图/持久化全复用）。
- `ChildTaskHost.submitBatch(JsonObject args)`：与 submit 同守卫（matches/closed/recovered/
  revision 必填且匹配），assignments 数组非空≤64；成功文案"已批量登记排队 N 条（不是完成）…"。
- `AgentLoop.submitCoordinatedChildBatch(JsonObject)`：与 submitCoordinatedChild 同锁
  （ToolExecutionGuard.exclusiveWorkspaceLock）+ 同"旧同步worker在跑拒绝混用"检查。
- `TaskTool`：Dispatcher 接口 additive 增 `submitBatch(JsonObject)`（旧实现默认方法回退
  errorWithoutSideEffects 提示升级？不——接口只有 AgentLoop 一个实现，直接加抽象方法并接线）；
  路由：`args.has("assignments")` → 批量；schema 增 assignments 数组 property（元素=单条结构化
  字段说明）；isStructured 增 assignments 判定；normalize 不动（元素级校验错误文案已可照着改）。
- 单条 submit/同步 task/后台 task 路径逐字不变。

## 3. J2 并行度配置（默认 2→4，用户确认）

- AgentLoop.coordination()：capacity 读 config `agent.childConcurrency`（默认 "4"，解析失败回退 4，
  clamp 1..16 与协调器校验同域）；运行时 AgentControl child_capacity 既有覆盖不动。
- 只读 background 池无限并行不动；同步 worker 混用防御不动。
- 默认值变化=放宽（用户明示要并行多个）；config 可回退 2/1。

## 4. J3 派发引导文案（prompt/工具描述，行为引导非强制）

- SystemPromptBuilder 子任务段：独立分工（dependsOn=[]）**一次 assignments 批量登记**，协调器按
  并行度自动同时执行；只读调查 mode=explore+run_in_background=true 可连续派发多个；
  **独立分工不必等前一个复核再派下一个**；child_wait 后集中 Read 证据+批量 child_review。
- TaskTool description：补"独立分工可用 assignments 一次批量登记并行执行"。
- ReplicaPromptTemplate 若有派发段同口径同步（实施时核查）。
- 复核门禁/证据要求/依赖释放语义**逐字不动**（只改派发节奏引导）。

## 5. J4 流式输出特征化锁定（headless）

- 新测试 `CoordinatedBatchParallelStreamTest`（照 BackgroundChildAgentTest rig）：
  主客户端一次 task(assignments=[2 条独立 explore/worker]) → 断言两子agent**同时 running**
  （子客户端闩锁延迟应答期间 child_status/快照双 running）；fake AgentUi 收到**两个 childId 交错**的
  START/CONTENT/TOOL_CALL 流式事件；返回后复核链路不变；单条派发路径特征化不变。
- 既有流式渲染（ChatView 标签行）与 H1 徽标不动，GUI 观感用户验收。

## 6. 测试与验收

**新增**：CoordinatedBatchParallelStreamTest（并行+交错流式+批量守卫：revision 过期整批拒绝、
空数组拒绝、重复 stepId 拒绝）；ChildTaskCoordinator 批量解析单测并入上类或既有协调器测试扩展。
**受影响旧套件（未经修改必须全绿）**：TaskToolArgumentsTest、TaskV2ToolsTest、
BackgroundChildAgentTest、SubAgentPlanModeTest、ControlLoopGuardTest、TaskStateChildVisibilityTest、
TaskStateChildProjectionTest、LateAdoptTest、AgentLoopArtifactsTest、既有 ChildTaskCoordinator/Host 测试。
**全量回归**：安静窗口 clean test ≥2972 只增不减，0F/0E。
**人工验收**：office 安装后派 4+ 独立分块 → 任务条"子Agent 4运行"、聊天流 4 路【子Agent】交错实时输出、
面板 4 行 running；追加引导后旧历史出条新分工上条（I1）。

## 7. 实施与交付顺序

1. J1 批量入口 + J4 测试 → 定向 → 独立提交；
2. J2 并行度配置 → 定向 → 独立提交；
3. J3 引导文案 → 定向（prompt 特征化测试若有）→ 独立提交；
4. I3 面板美化（I 批余项）→ 独立提交；
5. 安静窗口全量回归 → 基线写回 README/I/J 档 → docs 提交；打包另听用户指令（2.4.9）。

## 8. do-not-touch

协调器校验/依赖图/scope 重叠/证据门禁/复核 receipt 校验、隔离根与 OutputMerge、
同步 worker 混用防御、修订退休 cancelAll、后台池与 G2 非交互语义、H2 权限优先级、
ChatView 渲染管线、AgentLoop turn 结构（不移植上游 ToolCallExecutor 并行工具批——
写工具并行会破坏证据序与排他锁语义，收益由批量派发+队列泵等价覆盖）。

## 9. 实施记录（2026-11-25）

- 上游研读（github.com/aacw1/minion main）：TaskTool 仅 description、描述注明"可并行"；
  并行实现在 ToolBatch.runPooled（主 agent 一轮工具调用含多个 task 提交固定线程池并发，
  子 agent 内联串行防父池死锁）；流式为 SubAgentUi 编号事件 onSubAgentXxx(no,…)。
  本地等价覆盖：批量登记 + 协调器泵并行 + 既有 ChildAgentUi per-childId 流式（CONTENT/TOOL_CALL/DONE）。
- J1+J4 = a15b169：ChildTaskCoordinator parseAssignment 提取 + submitBatchArgs + Dispatcher.submitBatch 覆写；
  ChildTaskHost.submitBatch（守卫与单条 submit 逐字一致）；AgentLoop.submitCoordinatedChildBatch（同排他锁+同步 worker 混用检查）；
  TaskTool assignments 路由（legacy 逐元素 normalize+prepareStructured 自愈、revision 省略即当前修订；
  dispatcher 走 submitBatch）+ schema assignments/items + isStructured。
  新测试 CoordinatedBatchParallelStreamTest 10 条：会合屏障真并行、双 childId CONTENT 流、
  START 先于任一 DONE、过期修订/空批/重复 stepId/非对象/超 64 整批拒绝、单条特征化、弱模型变体自愈、dispatcher 路由。
- J2 = 65f47b3：Config.childConcurrency()（agent.childConcurrency 默认 4 夹 1..16）+ AgentLoop.childConcurrency 字段
  + SessionManager 接线；child_capacity 运行期覆盖语义不变（0..构造上限，越界拒绝）。ChildConcurrencyConfigTest 7 条。
- J3 = 3eb51a8：SystemPromptBuilder 子任务段与规则 6 批量并行优先；ReplicaPromptTemplate 核查无派发段（仅工具并行语义，不动）；
  COORDINATOR_PROMPT 本就并行口径不动。SystemPromptBuilderTest 增引导断言。
- 回归插曲 = c93a0e9：AgentLoopCompactTest stall 夹具估算距 150000 软阈值仅 8 token 余量，
  J1 schema 增量使软阈值压缩先触发、stall 路径断言落空；夹具用既有 setStableContextTokens(300000)
  抬高软阈值（他测试已有此旋钮先例），断言逐字不变。另：首轮全量 3 个 GUI 套件
  NoClassDefFoundError 为运行期瞬态污染（单独复跑全绿、class 文件事后存在），非代码缺陷。
- 定向套件：J 相关 56+7+8 条、I3 3 条、compact/context 26 条全绿。
- 全量回归基线（安静窗口 clean test，HEAD c93a0e9）：**3003 项 0 失败 0 错误**（40 环境跳过、380 类；
  上一基线 2972 只增不减）。已写回 README。