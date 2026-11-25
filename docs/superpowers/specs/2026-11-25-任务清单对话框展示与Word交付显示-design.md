# 任务清单对话框展示 + Word 交付显示修复 开发方案

日期：2026-11-25
状态：**备查规划稿**。实施由用户/其他实现者按本档执行；每项独立提交，**不打包**（除非用户明确要求）。
代码基线：main，**bf0308c**（AgentLoop 4225 行；测试类 361 个；全量回归新基线待安静窗口重测，
见第三批方案修订 3 §1——本档所有行号均为 2026-11-25 对 bf0308c 实测）。
关联文档：`2026-11-22-第三批架构模块化与内网能力增强-design.md`（修订 3）。
本档为**独立小批**，可插在第三批第一批之前做；与 Part A 重构无结构冲突（见 §5）。

两个需求：
1. **Part E**：AI 梳理的任务清单目前只能在"任务"弹窗（TaskDashboard）里看，要参考主流 agent
   在**对话框上方**常驻展示。
2. **Part F**：任务完成后交付的 Word（.docx）**有时在工作区显示、有时不显示**——排查代码找出
   全部静默丢弃点，逐项修复。

---

## 0. 现状结论速览

- 任务数据链路完整（TodoWriteTool → Session.todos；taskv2 → TaskState.workflow；
  TaskBoardModel.snapshot 已统一投影三类行），**缺的只是一个常驻 GUI 展示位**。
  对话列 `conversationColumn = new VBox(chatHost, inputView)`（MainWindow.java:246）中间可插入任务条，
  纯 GUI additive，零 core 改动。
- Word 显示"时有时无"**不是单一 bug，是链路上 8 个静默丢弃点叠加**（§4.0 根因表）。
  最主要根因：**用户插话/转向会推进 executionGeneration，长工具（Bash/Python 跑脚本写 docx）
  返回时产物被围栏静默丢弃**（AgentLoop.java:3986/4000/4010）；其次是**回执措辞不匹配**
  （收集器只认固定正则/JSON 字段）与**预览竞态**（文件还没写完就打开，ZipException 一刀切报
  "已损坏"且无重试）。全链路无诊断日志，事后无法排查。

---

## 1. 基线与纪律（沿用第三批方案修订 3 §1）

- 构建：JDK8 离线、零新 Maven 依赖；命令与 surefire 按属性名汇总见第三批档 §1.1。
- 回归：0F/0E 且用例数只增不减；**开工前先在安静窗口（关应用+关 IDE）重测全量基线并写回两档**。
- **additive + 异常回退旧路径，绝不收紧既有行为**；新增运行时行为默认关（v2.4 原则）。
  例外说明：E 任务条与 F4 扫描按钮是**纯展示层/用户显式触发**，不改变 agent 运行时行为，
  任务条推荐默认开（用户本次明确要求展示），留配置开关可关。
- **do-not-touch**：完成门禁与证据裁决语义（StopGate/StopAdjudicator/a371974 修复后的级联）、
  executionGeneration 围栏对**证据通道**的语义、ConfirmGate 分类、路径白名单
  （evidencePathAllowed，AgentLoop:109-113，**不放宽**）、stdout 256KB 截断、
  receipt-only 登记原则（不做自动 FS 扫描；F4 仅手动 opt-in）、
  共享知识库只读、各默认关开关。
- GUI 项手动截图 7 主题至少深浅各一（obsidian + light）；CSS 只用 `-wb-*` looked-up color
  （workbench.css），不写死颜色。
- 每项：定向新增测试 + 受影响旧套件**未经修改全绿** → 提交（中文 conventional commit）。
  F2 触碰 AgentLoop adopt 区，按 A 类纪律做特征化 A/B（搬移前后逐用例比对）。

---

## 2. 主流 agent 参考（设计层借鉴）

| 参考 | 做法 | 本档借鉴 |
|---|---|---|
| Claude Code（本地源码仅设计研究） | TodoWrite 工具维护清单，终端流里渲染 checklist；清单是"给用户看的进度面"，与门禁证据分离 | E：todo/任务行常驻输入框上方；F2：**展示登记与门禁证据分离**正是 CC 的语义——todo 展示从不影响停止判定 |
| Cursor | Plan/Todo 面板在输入框上方常驻，可折叠，点击展开明细 | E：折叠摘要行 + 展开明细 + 点击进详情（TaskDashboard） |
| Codex CLI | /plan 计划条目逐项打勾，进度徽标（n/m） | E：进度徽标 `已完成 n/m`；完成态视觉区分 |
| Devin | 计划面板持久可见，步骤状态实时刷新 | E：1s 轮询刷新（复用 TaskDashboard 既有模式，不新增事件通道） |
| CC 文件交付 | 工具结果即回执，UI 按回执渲染附件卡 | F：坚持 receipt-only；F1 只是把回执**措辞容错**做宽，F4 手动扫描是显式 opt-in 兜底 |

---

## 3. Part E：对话框上方任务条（TaskStrip）

### 3.1 现状链路（实测锚点）

| 环节 | 证据 |
|---|---|
| todo 数据 | `core/agent/TodoList.java:7-21`（TodoItem{text,done}，synchronized replace/markDone/render）；Session.todos |
| todo 写入 | `core/tools/TodoWriteTool.java`（create/update/mark；AgentLoop 注册；/new 清空）；**无 GUI 回调** |
| taskv2 数据 | TaskState.workflow（Step{id,title,status,dependsOn,…}），AgentControlTool/TaskV2Tools 维护 |
| 统一投影 | `core/agent/TaskBoardModel.java:48` `snapshot(Session, RuntimeJobRegistry)` → `List<BoardRow>`；BoardRow{kind=todo/task/background, id, title, owner, status, acceptance, recentOutput, exitCode}（:24-33）；`render(rows)`:87 |
| 现有展示 | `gui/TaskDashboard.java` 独立 Stage，1s Timeline 轮询 snapshot；入口"任务"按钮 MainWindow:285、Ctrl+T :505 |
| 插入位 | MainWindow:246 `conversationColumn = new VBox(chatHost, inputView)`；chatHost Vgrow ALWAYS(:249) |
| 备选插入位 | InputView（`gui/input/InputView.java`）composerShell:103，`setStatusNode`:312-316 把节点插到 composerShell 索引 0——**该槽已被 RunningIndicator 占用**（MainWindow:223-224），不抢槽 |
| 状态栏先例 | refreshStatusBar（MainWindow:621-693，2s Timeline）已统计 pending todos（:648-649，`synchronized(h.session.todos)`）——轮询读 todos 的线程安全模式照抄 |
| 可复用组件 | `gui/…/CollapsibleText.java`（摘要行+chevron+badge+body 折叠模式）；workbench.css `.input-frame`/`.pending-guide-row` 风格 |

### 3.2 设计

**新组件 `gui/TaskStrip.java`**（VBox），插入 conversationColumn 中间：

```java
// MainWindow:246 改为（additive，一行）：
conversationColumn = new VBox(chatHost, taskStrip, inputView);
// taskStrip 无内容时 managed=false + visible=false，布局与现状逐像素一致
```

- **数据源**：`TaskBoardModel.snapshot(session, jobs)`（与 TaskDashboard 完全同源，零 core 改动）；
  jobs 取当前 SessionHandle 的 RuntimeJobRegistry（与 TaskDashboard 取法一致）。
- **刷新**：1s Timeline 轮询（照 TaskDashboard 模式）；快照与上次相等则跳过重绘（防抖动）；
  窗口关闭/会话切换时 stop Timeline（防泄漏，照 TaskDashboard 生命周期）。
  会话切换跟随 manager.currentSession()（与状态栏轮询一致）。
- **折叠态（默认，单行 ~28px）**：`▸ 任务清单  [n/m]  当前：<第一条未完成行 title 截断 60 字>`；
  全部完成时 `✓ 任务清单 n/m 已完成`；无 todo 且无 task 且无 background → 整条隐藏。
- **展开态（点击摘要行切换，记忆到配置）**：≤8 行滚动列表，每行
  `[✓/▶/○] title（kind=task 附 owner/status 徽标；background 附 exitCode）`；
  行点击 → 打开 TaskDashboard（复用 MainWindow:285 的构造调用）；
  展开最大高度 160px，超出滚动，**不挤压输入框**（inputView 高度不变，chatHost 让位）。
- **样式**：`.task-strip`/`.task-strip-row`/`.task-strip-badge` 新 CSS 类，全部 `-wb-*` 取色；
  完成行用弱化色，进行中行用强调色；7 主题自动适配。
- **配置**：`ui.taskStrip.enabled` **默认 true**（纯展示层，用户明确要求；false 时组件不创建，
  布局与现状一致）；`ui.taskStrip.expanded` 记忆展开态，默认 false。
- **不做**：不改 TodoWriteTool/TaskState/TaskBoardModel；不新增 AgentUi 事件；
  不在任务条上做勾选交互（只读展示，操作仍归 TaskDashboard/agent）。

### 3.3 开发步骤

1. 新建 TaskStrip（纯 GUI）+ CSS；MainWindow 插入一行 + 配置读取。
2. 摘要/徽标/隐藏决策抽纯静态方法（可 headless 单测）。
3. 定向测试 + 手动截图（7 主题至少 obsidian/light；折叠/展开/全完成/空态/长标题/后台行 6 态）。
4. 全量回归 → 提交。

**测试**：`TaskStripModelTest`（纯逻辑：n/m 计数、当前行选取、空态隐藏、全完成文案、截断）；
GUI 手动清单留截图；Timeline 关闭断言（stage close 后 isStopped）。
**验收**：任务条与 TaskDashboard 内容一致（同源快照）；关闭开关后布局与 bf0308c 逐像素一致；
输入框/RunningIndicator/状态栏行为零变化。
**规模**：约 250 行新增 + CSS；约 1.5 人日。

---

## 4. Part F：Word 交付显示修复

### 4.0 根因表（全部实测，"时有时无"的解释）

链路：工具执行 → GeneratedArtifactCollector 收回执 → AgentLoop.adoptToolArtifacts/adoptArtifacts
登记（Session.registerArtifact）→ ui.onArtifactCreated → MainWindow:369-378 自动打开 →
ArtifactPreviewPane 异步读（DocumentPreviewLoader docx=ZipFile+SAX）→ 工作区成果卡。

| # | 丢弃点 | 证据 | 触发条件（为何间歇） | 修复项 |
|---|---|---|---|---|
| R1 | **回执措辞不匹配**：Bash/Python/Shell 只认 SAVED_RECEIPT 正则(:28)、JSON `artifacts` 数组(:85-88)、Markdown 链接；Document/Excel 等只认 `outputPath` 且需 StopGate.mutationEvidence(:48-49)；ok==false 一律不收 | GeneratedArtifactCollector.java:37-116 | 模型/脚本输出"已生成 report.docx"等措辞不在正则内 → 文件在盘上但无登记 | F1 |
| R2 | **generation 围栏静默丢弃**：`adoptToolArtifacts` 开头 `if(!owner.current(this))return;`；登记循环 `if(!owner.current)break;` | AgentLoop.java:3986、4000 | **用户插话/转向(:1596)、中断(:1551)、切模型(:1102)都推进 generation**；长 Bash 写 docx 期间用户说一句话 → 产物整个丢掉。最主要根因 | F2 |
| R3 | **UI 推送跳过**：`ui.onArtifactCreated` 前再查 `if(!owner.current)return;` | AgentLoop.java:4010 | 登记成功但推送前 owner 又变 → 工作区不弹卡（快照里有、界面上无） | F2 |
| R4 | **预览竞态无重试**：onArtifactCreated 触发时异步写文件可能未完成；ZipFile 读到半写文件 → ZipException → 一刀切"已损坏"IOException，**无重试**；conversionAttempted 一次性 | DocumentPreviewLoader.java:153-158；ArtifactPreviewPane.java:473-474 | 大 docx 写入慢/杀毒扫描占用 → 首次打开必败，之后永不重试 | F3 |
| R5 | **预览线程池静默丢任务**：reader/conversion 两池均 core=1、**queue=1、DiscardOldestPolicy** | ArtifactPreviewPane.java:73-78 | 连续交付多个文件/快速切换 → 排队任务被静默顶掉，卡片空白无错误 | F3 |
| R6 | **路径限制+异常吞掉**：allowed 仅 workDir+extraAllowedDirs+tempDir；add() 末尾 `catch(Exception){/*注释*/}` 无任何日志 | GeneratedArtifactCollector.java:164-171；AgentLoop:109-113 | 脚本把 docx 写到白名单外/路径含非法字符 → 无声无息 | F1(日志)+F4(兜底) |
| R7 | **tmp 三天清理**：.session/tmp 超 3 天删除，不查登记表 | Main.java:31-32 + OutputDump.java:71-81 | 交付物落在 tmp → 三天后预览报"文件不存在" | F5 |
| R8 | **全链路零诊断**：收集/采纳/丢弃无 DiagnosticLog 标签（现有仅 late-tool-result:3971/preview 等） | DiagnosticLog 调用点实测 | 用户报"没显示"时无从查起 | F6 |

### 4.1 F1 收集器措辞容错 + 路径兜底（S，约 120 行；低风险先做）

`GeneratedArtifactCollector` additive：
- **扩展回执正则**（新增 pattern，旧 SAVED_RECEIPT 逐字保留）：中文措辞
  `已(生成|保存|写入|导出|输出)(到|至|为)?[:：]?\s*<path>`、`文件[:：]\s*<path>`、
  `<path>（已生成/已保存）` 等；path 捕获支持带引号/空格路径（Windows 现实）。
- **stdout 路径兜底扫描**（仅 Bash/Python/Shell 且 ok==true）：在 stdout（仍 256KB 截断内）
  提取**交付扩展名**（.docx/.doc/.xlsx/.xls/.pptx/.ppt/.pdf/.md/.csv/.html/.png/.jpg）的
  绝对/相对路径 token → 逐个走**现有 add() 校验**（allowed 白名单 + isRegularFile + toRealPath
  复核，一个不放宽）→ 存在才登记。**只认盘上真实存在的文件，不猜**。
  上限：每次工具结果兜底 ≤10 条（防 stdout 刷路径轰炸）；kind=DELIVERABLE，sourceTool 照旧。
- **诊断**（F6 一部分）：add() 的 catch 与每个 return 分支记
  `DiagnosticLog.info("artifact-collect", "tool=… path=… drop=reason")`（reason∈
  not-allowed/not-regular-file/parse-failed/too-many/ok-false）；命中记 `accept=…`。
  措辞扩展命中的记 `pattern=extended|fallback-scan`（便于评估误报率）。
- **不做**：不放宽 allowed；不改 ok==false 语义；不改 Document/Excel 的 mutationEvidence 前提；
  不自动扫文件系统（F4 才是手动扫描）。

**测试**（GeneratedArtifactCollectorTest 扩展，旧用例未经修改全绿）：
新措辞 fixture 矩阵（含引号/空格/中文冒号）；兜底扫描命中/不存在文件不登记/白名单外仍拒绝/
超 10 条截断；ok==false 零登记（特征化）；诊断标签捕获。
**验收**：旧套件全绿；新措辞样本登记率提升留证；误报=0（兜底只认存在文件）。

### 4.2 F2 迟到采纳（late-adopt）：展示登记与门禁证据分离（M，约 80 行；核心根因）

**设计原则**（参考 CC：todo/附件展示从不影响停止判定）：
`Session.registerArtifact`（Session.java:75-121）实测为**纯登记表 + 历史快照拷贝，无 taskState
证据副作用**——门禁证据走 stopReceipts/recordStop 独立通道。因此"迟到登记"可以安全地
只进展示面，不进证据面。

`AgentLoop.adoptToolArtifacts/adoptArtifacts`（:3985-4016）改造：
```java
// 伪代码：owner 失效不再整体丢弃，降级为"迟到展示登记"
boolean stale = !owner.current(this);
if (stale && owner.sessionChanged(this)) { log("artifact-late-adopt","drop=session-changed"); return; }
// /new、restoreSession 换了会话 → 仍旧丢弃（防旧任务文件污染新会话登记表）
// 登记循环：去掉 if(!owner.current)break —— 同会话内迟到产物照常 registerArtifact
// UI 推送：去掉 if(!owner.current)return —— path-only 事件照发（GUI 按快照解析，天然安全）
// 迟到产物打标：artifact.late = true（GeneratedArtifact additive 字段，随快照持久化）
DiagnosticLog.info("artifact-late-adopt", "path=… stale=… generation=…");
```
- **证据面零变化**：late 产物**不进 stopReceipts、不计入 stopHistoricalEvidenceCount、
  不参与 StopGate 快照**（实施时核实 adopt 调用点上下游：receipts 记录在工具结果通道，
  与 adopt 分离——若发现任何证据计数读 session.artifacts，则 late 条目在该枚举处排除，
  并加特征化断言）。artifactFence/synchronized 锁序**逐字保留**（只删 guard，不改锁）。
- **ToolOwner additive**：创建时捕获 session 引用（或等价会话标识），供 sessionChanged 判定；
  现有 current(this) 语义不动（其他调用点照旧）。
- **同会话插话场景**（最主要根因 R2/R3）：generation 推进但会话未变 → 文件照常登记+弹卡。
- **特征化 A/B（A 类纪律）**：改前留 surefire XML → 改 → 重跑比对。
  套件：EvidenceBasedCompletionTest、StopGateTest、StopAdjudicatorCharacterizationTest、
  UnifiedEndStateMachineTest、UnifiedStopLoopTest、CompletionHeuristicBoundaryTest、
  GeneratedArtifactCollectorTest、ArtifactPreviewPaneTest + 全量。
  **新增 LateAdoptTest**：工具执行中推进 generation（模拟插话）→ 产物仍登记、UI 事件仍发、
  **门禁证据计数与改前逐字一致**；/new 后会话变更 → 仍丢弃；late 打标持久化回环。

**验收**：复现脚本——长 Bash（sleep 后写 docx）执行中用户插话 → 工作区出现卡片且预览成功；
门禁特征化逐用例一致；A/B 不干净 → revert 本提交（F1/F3 不受影响）。

### 4.3 F3 预览健壮性（M，约 150 行；纯 GUI）

`ArtifactPreviewPane` + `DocumentPreviewLoader`：
- **有界重试**（R4）：加载失败且属"文件存在但读失败"（ZipException/文件锁/NotFound）→
  后台重试 ≤3 次、间隔 500ms，重试前检查**文件大小两次采样稳定**（写盘完成启发式）；
  权限/白名单类错误不重试。重试期间卡片显示"文件写入中，正在重试…"而非"已损坏"。
  3 次仍败 → 可操作错误 + **手动重试按钮**（新增；点击重置 conversionAttempted 一次性标记）。
  重试在 reader 池内执行，**不阻塞 FX 线程**；generation 守卫(:448/:481)照旧——
  用户已切走则放弃重试。
- **池策略**（R5）：reader/conversion 两池 queue 1→**8**；提交前**同 path 合并**
  （已有同路径待执行任务 → cancel 旧 Future 再入队，替代盲目 DiscardOldest）；
  仍保留 DiscardOldestPolicy 作最后兜底，但**丢弃时记日志 + 卡片显示"预览被跳过，点击重试"**
  （不再静默空白）。
- **错误卡统一加"重试"与"用关联应用打开"**（后者已有打开链路则复用）。
- **不做**：不改 validateZip 安全护栏（>8MiB/ratio>200/CRC 检查逐字保留）；
  不改 .doc COM 转换 45s 超时；不引入 POI。

**测试**（DocumentPreviewLoaderTest/ArtifactPreviewPaneTest 扩展，旧用例全绿）：
半写文件 fixture（先写一半→触发加载→补完）重试成功；3 次失败出重试按钮；
大小不稳定不提前读；同 path 合并 cancel 断言；队列满丢弃出可见提示；
generation 切走放弃重试；护栏（加密/超比率 zip）仍一刀切拒绝（特征化）。
**验收**：复现"交付瞬间点开"不再永久报损坏；7 主题截图（重试态/错误态）。

### 4.4 F4 工作区手动扫描（S，约 100 行；opt-in 兜底）

- ArtifactPreviewPane 工具区新增**"扫描工作区文件"按钮**（用户显式点击才扫，绝不自动）：
  扫 workDir（含 extraAllowedDirs）下交付扩展名文件，深度/数量护栏（≤2000 文件/≤3 层，
  照 WorkspaceFilesPane 既有上限 :94），排除 .git/.minion/.session（照 :20/:95 隐藏规则）。
- 结果列出**不在 session.artifacts 登记表**的文件，标"未登记"徽标；
  点击 → 走 registerArtifact 登记（**late=true，不进证据**，与 F2 同通道）+ 预览。
- 复用 `WorkspaceFilesPane`（现为死代码，仅测试引用）的扫描/过滤逻辑——
  机械抽包内工具类或直接引用，不复活整个旧面板。
- **不做**：不放宽白名单；不自动扫描；不改 receipt-only 默认原则。

**测试**：扫描命中/排除目录/护栏上限；未登记判定；登记走 late 通道且证据计数不变；
按钮不存在时（配置关）零行为变化。`ui.workspaceScan.enabled` 默认 true（纯手动 GUI）。

### 4.5 F5 清理豁免（S，约 20 行）

OutputDump/.session/tmp 三天清理（OutputDump.java:71-81）删除前查
**当前及可枚举会话的 artifacts 登记表**（含 snapshotPath 引用）：被引用 → 跳过 +
`DiagnosticLog.info("artifact-cleanup-skip", path)`。只加豁免判断，不改清理周期/范围。
**测试**：被登记文件豁免；未登记照常清理；登记表读取失败 → 保守跳过该文件（宁可不删）。

### 4.6 F6 诊断与排查指引（S，约 40 行，随 F1/F2/F3 落地）

统一标签：`artifact-collect`（F1）、`artifact-late-adopt`（F2）、`preview-retry`/`preview-drop`
（F3）、`artifact-cleanup-skip`（F5）。
**排查顺序**（写入 docs/ 排查指引或 README 小节）：用户报"Word 没显示"时——
1. 查 `artifact-collect`：有没有 accept？drop 原因是什么？（R1/R6）
2. 查 `artifact-late-adopt`：是否 stale 丢弃？（R2/R3）
3. 查 `preview-retry`/`preview-drop`/既有 preview 标签：登记了但预览败？（R4/R5）
4. 查 `artifact-cleanup-skip` 与文件是否还在盘上（R7）；
5. 都没有 → 用 F4 手动扫描兜底找回。

### 4.7 F 项实施顺序与规模

| 序 | 项 | 规模 | 依赖 | 风险 |
|---|---|---|---|---|
| 1 | F6 诊断骨架 + F1 收集容错 | S+S | 无 | 低（纯 additive，先让问题可查） |
| 2 | F3 预览健壮性 | M | 无 | 低（纯 GUI） |
| 3 | F2 迟到采纳 | M | F6 | **中（触 AgentLoop，特征化 A/B，不干净即 revert）** |
| 4 | F5 清理豁免 | S | F6 | 低 |
| 5 | F4 手动扫描 | S | F2（late 通道） | 低 |

合计约 3.5 人日；加 Part E 约 1.5 人日，**本档总计约 5 人日**。
每步定向测试+旧套件全绿；批次末安静窗口全量回归 + README/本档同步。

---

## 5. 与第三批方案的关系

- 本档**独立成批**，建议插在第三批第一批之前（用户当前痛点优先）。
- F2 只改 AgentLoop 尾部 adopt 区（:3985-4016）+ ToolOwner additive 字段，
  **不动 runTurn 结构、不动门禁**——与第三批 A1/A2/A3 搬移区不重叠；
  若实施时 A 批已先做，按符号名重新定位（勿盲信本档行号）。
- E 任务条与第三批 D5"计划建议条"（问题卡区域非阻塞建议）位置不同、互不冲突；
  D5 实施时可复用 TaskStrip 的 CSS 基调。
- 回归基线共用：开工前重测一次，两档同步写回。

## 6. 验收总纲

- 全量回归 0F/0E、用例数只增不减（新基线重测后为准）；F2 特征化 A/B 逐用例一致留证。
- **E**：任务条与 TaskDashboard 同源一致；空态/关闭开关时布局与 bf0308c 逐像素一致；
  7 主题截图；输入框/RunningIndicator/状态栏零变化。
- **F**：复现脚本两条全过——
  ① 长 Bash 写 docx 期间用户插话 → 卡片出现+预览成功（R2/R3）；
  ② 交付瞬间自动打开半写 docx → 重试后正常显示（R4）；
  门禁证据语义零变化（特征化）；白名单/护栏/截断零放宽；
  排查指引可用（按 F6 顺序能定位每一类丢弃）。
- 不打包；提交由用户决定。

## 7. 决策记录与推荐默认值

推荐默认（均可配、均带回退）：
`ui.taskStrip.enabled=true`（纯展示，用户明确要求）、`ui.taskStrip.expanded=false`、
`ui.workspaceScan.enabled=true`（仅手动按钮）、F1 兜底扫描上限 10 条/次、
F3 重试 3×500ms + 队列 8、F5 豁免保守跳过。
设计决策：E 插 conversationColumn 不抢 setStatusNode 槽（RunningIndicator 占用）；
E 用轮询不用新事件（零 core 改动，TaskDashboard 先例）；
F2 展示登记与门禁证据分离（registerArtifact 实测无证据副作用，CC todo 语义同构）；
F4 手动 opt-in 不破坏 receipt-only 原则；late 产物打标随快照持久化。
待实施时核实项：BoardRow 渲染字段取舍（3.2）、adopt 上下游证据计数是否读 artifacts（4.2）、
WorkspaceFilesPane 抽取方式（4.4）、TaskDashboard 的 jobs 取法（3.2）。

---

## 8. 实施状态（2026-11-25 开发完成，未打包）

实施者：qodercn（E/F1–F5 逐项独立提交）；收尾（批次末全量回归 + README/本档同步）由本 agent 接续完成。
F6 诊断随 F1/F2/F3/F5 落地（artifact-collect / artifact-late-adopt / preview-retry / preview-drop /
artifact-cleanup-skip 标签均在代码中实测存在）。

| 项 | 提交 | 新增/扩展测试 | 要点（实测核对与设计一致） |
|---|---|---|---|
| E 任务条 | 9935178 | TaskStripModelTest 10（新） | TaskStrip 插 conversationColumn（MainWindow:254）；ui.taskStrip.enabled 默认 true、expanded 记忆；窗口隐藏 stop Timeline(:295) |
| F1 收集容错 | 807fac8 | GeneratedArtifactCollectorTest +71 行 | EXTENDED_RECEIPT 中文措辞 + FALLBACK_TOKEN 兜底扫描（≤10 条，走原 add() 白名单校验）；artifact-collect 诊断 |
| F3 预览健壮 | fc18364 | ArtifactPreviewPaneTest +165 行 | 重试 3×500ms + 大小稳定采样；两池队列 8；重试按钮重置 conversionAttempted；preview-retry 诊断 |
| F2 迟到采纳 | 99b7964 | LateAdoptTest 5（新）+ AgentLoopArtifactsTest 扩展 | owner 失效但会话未变 → late=true 展示登记，证据通道零变化；会话变更仍丢弃；artifact-late-adopt 诊断 |
| F5 清理豁免 | 989f382 | ArtifactCleanupGuardTest 2（新）+ OutputDumpTest 扩展 | 登记表引用文件豁免三天清理，读取失败保守保留；artifact-cleanup-skip 诊断 |
| F4 手动扫描 | 59c343e | LateAdoptTest/ArtifactPreviewPaneTest/WorkspaceFilesPaneTest 扩展 | ui.workspaceScan.enabled 默认 true；扫描走 late 通道（sourceTool=WorkspaceScan），白名单不放宽 |

**批次末全量回归（2026-11-25 安静窗口实跑定稿，本 agent 接续执行）：2933 项 0F/0E/40S/373 类**
（`mvn -o clean test`，BUILD SUCCESS；用户关闭 IDE 后全量 clean 重跑，非混合聚合）——
较第一批收尾基线 2895/370 净增 38 项 3 类（TaskStripModelTest 10 + LateAdoptTest 5 +
ArtifactCleanupGuardTest 2 + 既有 5 个测试类扩展 21），跳过数 40 不变。**E/F 收尾新基线 2933 项/373 类**，
供第三批第二批沿用（只增不减）。

**待人工验收（GUI，无法自动化点击）**：任务条折叠/展开/空态/全完成 7 主题截图（至少 obsidian+light）；
Word 两条复现脚本（长 Bash 写 docx 期间插话 → 卡片+预览；交付瞬间打开半写 docx → 重试后正常）；
F4 扫描按钮与"未登记"徽标；F3 错误卡重试按钮。