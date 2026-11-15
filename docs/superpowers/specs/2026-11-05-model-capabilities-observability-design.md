# 批次 E：模型能力档案与可观测性

## 范围与不变量

只改 ModelProbe、DeepSeekClient、UsageTracker、ContextBudget、ReliabilityPanel 及本批新增类/测试；不改主循环、任务状态、会话装配、ContextManager、ModelConfig、总计划或 README。不运行共享 target 的 Maven，不提交、不打包、不覆盖 dist。Java/JavaFX 8，无新增依赖；150000 默认压缩目标和主子同模型不变。

## 设计

- 现有手动自检入口继续使用。配置指纹沿用 ModelProbe.fingerprint，涵盖真实 URL、模型、密钥摘要、协议及配置；不落盘 URL/密钥/请求正文。档案另记探测实际配置（关闭思考、输出上限 1024）的指纹及适用范围，不把小请求当作窗口证明。
- 能力分为 supported / unsupported / unknown。提示词 JSON 仅证明该样本通过；结构化输出另用 response_format=json_object 实测。只有客户端确认 SSE 解析、完整返回实际 usage 才能证明对应能力。未测图片、网络失败、格式偏离、截断均不推断支持。SFM 为远端有状态 Agent，拒绝自检向远端会话注入合成任务。
- 档案只在用户探测后保存；按指纹直接读取单个文件，无启动扫描/自动联网。默认有效 7 天，可本地作废/重测；过期能力返回 unknown，保留原证据。
- 客户端每次 HTTP 尝试单独记录首有效增量、响应头、首增量至网络结束、总网络跨度、finish、分类错误、本地分项粗估、实际 usage 字段；无服务端分词时不伪造分项实际值。同步 HTTP 无本地 dispatcher 排队，服务端排队不可分离，记 unknown，不标 0。
- UsageTracker 保留旧总量 API，另分真实/估算累计。校准只接收完整真实 usage 与同请求分项估算，按请求指纹隔离；只供展示，不修改压缩阈值、模型或服务端窗口。
- 新增可选阶段计时接口供主流程接线：queue/tool/retrieval/compression/final_verification 未观测为 unknown；不自动围绕已有流程打点或假填数字。
- HTTP/格式/断流分类通过 LlmException 兼容子类及恢复建议接口暴露。明确图片 400 不做 tool_choice 回退；实例内仅缓存完全相同请求的图片协议拒绝，用户可手动清除后重试。不删除图片、不篡改 reasoning_content/tool_calls、不自动更换模型。

## 验证计划

在独立 target/batch-e-* 子目录使用本机 JDK8 javac/JUnitCore；MockWebServer 和离线假客户端，不调用真实服务。覆盖配置指纹、过期/手动作废、unknown、结构化实际参数、SSE计时、真实/估算 usage、图片拒绝去重、协议配对原样、坏 JSON/截断、并发请求统计、旧入口回归。最终请主流程统一运行定向与全量 Maven；真实内网模型与 Win7 真机未验证。

## 接线及最终证据

实施完成后在本文件追加具体接口、绝对路径、测试日志和未实现项。

## 落地状态（2026-11-05）

### 已实现

1. ModelProbe 旧 run/request/evaluate/fingerprint 方法保持兼容；新增真实 response_format=json_object 合成测试，不能据提示词 JSON 冒充结构化接口支持。工具调用、可选图片、SSE、真实 usage 证据以三态存档。SFM 有状态远端 Agent 在发请求前拒绝隔离能力自检。
2. ModelCapabilityStore 在 `<项目>/.minion/model-capabilities/<指纹>.json` 惰性读写，保留配置指纹及实际探测指纹，不保存 URL、密钥、项目正文、自由错误体。7 天有效期、人工本地作废、手动重测；没有启动探测/扫描。GUI 原“手动模型自检”已写档案，新增查看、作废、遥测展示及清除图片拒绝记录按钮；原历史结果查看会展示当前过期/作废状态。
3. DeepSeekClient 对每次流式尝试及非流式结束核验记录内存快照，最多 32 条；首有效思考/正文/工具增量才算首 token，空字符串和空 tool_calls 不算。网络跨度与请求构建/本地统计后的总客户端跨度分开；流式网络跨度仍含流式回调处理，不冒充纯服务端生成时间。无输出、未观测排队均为 -1/unknown。
4. 本地输入分项包含正文、工具结果、schema、工具参数、思考、图片；缺 usage 时不会漏计 schema/工具输出参数。API usage 缺字段、空对象、负数/非整数与本地估算分开；部分真实字段保留，混合总量 estimated=true，不参与完整 usage 校准。UsageTracker 旧累计 API 保留，新增 reported 与 estimated/mixed 源统计；旧存档未标来源的量单独列 unattributedHistorical，不倒推为真实。
5. 同请求实际输入与分项估算总量比可在客户端直接展示；UsageTracker 提供按指纹隔离的同请求累计比率接口，不修改配置、150K 压缩线或模型。分项实际 token 仍 unknown，不从总 usage 比例摊分假造实际图片/schema成本。
6. 分类恢复接口覆盖认证、限流、连接/超时、图片拒绝、上下文超限、参数不支持、格式、截断及取消；尊重原 retryable。断流仍返回 incomplete/length 旧契约。明确图片协议 400 不触发 tool_choice 回退；实例内有界缓存完全相同图片请求的拒绝，重复调用不联网，可手动清除。历史 reasoning_content 与 tool_call/tool 配对不改、不删图、不换模型。

### 未实现 / 不冒充完成

- E05 工具/schema/技能按需加载与缓存、长结果按页取回及效果对照，本批没有接主流程；不据此勾选整个 E。
- PhaseTimings 仅提供真实打点接口，QUEUE/TOOL/RETRIEVAL/COMPRESSION/FINAL_VERIFICATION 仍需主流程持有当前任务实例并实际围绕执行接线；未观测阶段是 unknown。
- 当前任务 token baseline、主子客户端合并、任务归属、结束核验总阶段耗时由主流程整合。客户端内存快照不是当前任务独占统计，也不包含其他独立子客户端；不替代 parent_session_delta 口径。
- 服务端最大窗口、正常思考模式的全部能力、严格 JSON Schema、真实服务端排队/纯生成耗时未证明。档案是关闭思考/1024 输出的合成小样本证据。
- 未运行 Maven 全量，未调用真实内网模型，未做 Win7/Python3.7.6 真机验证；没有发布产物或新增运行时依赖。

## 稳定 API 与主流程接线位置

- `new ModelCapabilityStore(Path).load(ModelProbe.fingerprint(actualConfig))`；`ModelCapabilityProfile.state(Capability, now)` 在过期/缺失时为 UNKNOWN；`describe(now)` 区分有效状态与历史证据。`invalidate(fp, now)` 仅本地作废，`save(ModelProbe.profile(report))` 仅在用户显式探测后。读取 IOException 应显示 unknown/读档失败，不能触发探测。所有 SUPPORTED 只在档案 scope 所列探测模式内成立。
- `((DeepSeekClient) client).telemetry().recent()` / `recentAfter(cursor)` 返回深拷贝；`observationId` 是该客户端完成顺序游标。首 ID > cursor+1 表示有界历史淘汰，需要报告采集缺口。`attempts()` 含被缓存拒绝的逻辑尝试，后者 `cachedImageRejection=true`、网络耗时 unknown，不代表又发了一次 HTTP。更换客户端须换游标。
- 主要字段：`kind`、`headersMs`、`firstTokenMs`、`networkGenerationMs`、`networkTotalMs`、`clientTotalMs`、`queueMs/serverQueueMs`、`finishReason`、`failure/recovery`、`apiUsage`、`resolvedUsage`、`localInputEstimate`、`totalInputCalibrationRatio`。`stop_review_nonstream` 的首 token 为 unknown；不推算未观测阶段。
- `UsageTracker.observeCalibration(fp, ContextBudget.estimate(exactMessages, exactSchemas), actualUsage)` 不重复累计 usage；应在主循环 `session.usage.record(usage)` 后调用，必须是刚发出的同一请求快照和真实 schema。`breakdown()` 查看来源与总量比，估算/混合拒绝校准。原 `ContextManager.observeInputTokens` 路径未改。
- `PhaseTimings.begin(Phase)` 返回 AutoCloseable Span（重复 close 不重复计数），或 `record(Phase, measuredMs)`，`snapshot()` 未观测=-1。当前任务应有独立实例，不能仅依据 executionPhase 状态名伪造耗时。
- `LlmRecovery.classify(e)` 与 `action(e)` 只给建议；`action(e)` 保留 DNS 等永久故障 retryable=false；不执行自动恢复。

主流程应修改的位置（行号仅当时参考，按方法定位）：

1. `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\gui\session\SessionManager.java`：`newLlm`（约668行）无需更改构造参数；`loadSession/createSession`（约328/565行）如需装配任务观测引用，放在客户端和 runtimeModelConfig 创建后；不要在启动历史加载/会话枚举中读所有档案或发探测。
2. 同文件 `replaceSessionModel`（约849行）更换客户端后清游标/能力缓存；运行中设置延后刷新时用 `session.runtimeModelConfig.copy()` 匹配实际客户端，而不是 models 中已编辑的新配置。`configureAgentRuntime`（约673行）子客户端独立观测，可在回收前按 childId 采集；同模型原则不改。
3. `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\agent\AgentLoop.java`：usage 记录后接同请求校准；围绕真实工具 `executionGuard.execute`、检索、各 compact 分支及 `StopGate.evaluate` 接 PhaseTimings，失败/取消也在 finally 收口。
4. 本批未编辑上述接线文件，也未编辑 TaskState、SessionManager、ContextManager、ModelConfig。主流程若追加接线，需统一重新定向及全量验证。

## 本批文件清单（绝对路径）

### 既有文件增量修改

- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\agent\ModelProbe.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\DeepSeekClient.java`（保留进入本批之前的脏基线改动）
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\UsageTracker.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\context\ContextBudget.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\gui\ReliabilityPanel.java`

### 新增辅助类

- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\ModelCapabilityProfile.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\ModelCapabilityStore.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\RequestTelemetry.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\PhaseTimings.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\llm\LlmRecovery.java`

### 新增测试及设计

- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\llm\ModelCapabilitiesBatchETest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\llm\DeepSeekTelemetryBatchETest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\llm\UsageObservabilityBatchETest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\gui\ReliabilityModelCapabilitiesUiSmoke.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\docs\superpowers\specs\2026-11-05-model-capabilities-observability-design.md`

## 测试证据

- 使用本机 Zulu Java/JavaFX 1.8.0_222，以 javac/JUnitCore 在 `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-e-isolated` 独立输出，不在共享 target/classes 写入，不跑 Maven。
- core/llm 全部 11 个 Test 类 + 旧 ModelProbeTest：**110 项，0 失败、0 错误**。新测试仅 MockWebServer/离线数据，覆盖三态、指纹各字段、过期/作废、结构化真实参数、无启动探测、usage 真/估分项、流式首有效增量、断流/格式、图片400去重/手动清除、同模型/历史协议配对、并发和有界游标。
- 独立真实 JavaFX8 控件 smoke 通过：实际探测按钮已注册事件（未执行计费确认后的真实探测）、档案查看与作废按钮持久化生效、遥测展示与拒绝记录清除、1000x700 隐藏布局；这些查看按钮无网络请求。重复验证时修正测试工作空间从默认 `.` 改成明确临时绝对路径，防止测试夹具污染项目；本批首次测试生成的单个已作废夹具已移入独立 target 证据目录，未覆盖旧项目资料。
- 独立目录 391 个已编译 class 的最高 major=52；仅证明本机该编译集合，不冒充全部项目或 Win7 真机兼容。
- 日志：`C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-e-isolated\final-compile.log`、`junit-expanded.log`、`javafx-smoke.log`、`bytecode.log`、`jdk-version.log`；机器可读清单为同目录 `validation.json`，含本批源文件 SHA256。
- 统一复测建议：主流程在所有模块落地后执行 Maven compile、上述 12 个测试类的定向测试，再跑全量测试。JavaFX smoke 是独立 main 入口，不在无桌面 CI 自动启动。没有提交、打包或覆盖任何旧 JAR/ZIP/dist。