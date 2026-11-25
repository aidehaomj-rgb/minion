# 子 agent 运行可视化（H1）与对话级权限选择（H2）设计

日期：2026-11-25（HEAD 39b36d4 + G1 6c55283 + G2 eb30e73 之后）
状态：**用户直接指令**（"让子agent显示运行，让我看到执行情况；参考主流agent配置对话权限选择，
选自动审批或完全授权后本对话主/子agent不再提问授权；做好再打包 2.4.8"）。
原则：additive、默认行为零变化、deny 规则永不放宽、会话态不落盘。

## 1. 现状核查（HEAD 实测）

### 子 agent 可视化已有三层（办公室看不到 = 旧包 + G1/G2，非渲染缺失）
1. **聊天流**：ChatView.onChildEventFx（ChatView.java:437）按【子Agent xxxxxxxx】标签渲染
   START/THINKING/CONTENT/TOOL_CALL/TOOL_RESULT/DONE/ERROR 折叠行，流式实时。
   事件链：SubAgentLoop → AgentLoop.ui.onChildEvent → SessionController:214 → EventList → ChatView。
   BackgroundChildAgentTest.backgroundChildEmitsLiveUiEventsWithoutDuplicateStart 锁定。
2. **任务面板**：TaskDashboard 子Agent TableView（:29）+ 1s 轮询；TaskStrip（输入框上方）
   经 TaskBoardModel.snapshot 直读 state.children 全集（**不经修订过滤**，G1 不影响面板行）。
3. **主 agent 上下文数量**：render "当前任务子Agent数量" 经 currentChildRefs —— G1 已修。

### 权限链路
- ConfirmGate **工作空间级共享**（SessionManager.WorkspaceCtx.confirmGate，:166/:580），
  主 agent 与子 agent 共用同一实例（AgentLoop:1023 传入 SubAgentLoop）。
- 已有 `setSessionPermissionMode`（会话内存态模式覆盖，不落盘；当前仅 HeadlessRunner:177 调用）
  与 APPROVE_SESSION"本会话允许"（sessionBypass，同为空间级会话态）——**会话=工作空间上下文**
  是既有语义，本档沿用，不新造 per-chat 隔离。
- GUI 现有 Shift+Tab 循环的是**全局 config permission.mode（落盘）**（SessionManager:2354），
  无对话级免确认选择。

## 2. H1 子 agent 运行可视化（补齐折叠态可见性）

聊天流/面板已具备；唯一增量：**TaskStrip 折叠摘要加运行中子Agent徽标**——不展开也能看到
"子Agent N 运行"。展开行已有 ▶ running + mode·status；点击行进 TaskDashboard 看全量。

- `TaskStrip.summaryText(rows)`：统计 KIND_TASK 且 status=running 的行数 n>0 时，
  摘要追加 ` · 子Agent N运行`（完成态摘要同样追加，便于看到收尾）。纯静态，headless 可测。
- 不改 TaskBoardModel/事件通道/轮询机制；无运行子agent时文案逐字不变。

## 3. H2 对话级权限选择（自动审批 / 完全授权）

### 3.1 语义（参考主流 agent 会话权限模式，按本仓库既有管线落地）

| 选择 | 行为 | 实现 |
|---|---|---|
| 默认（逐次确认） | 现状逐字不变 | clearSessionPermissionMode + autoApprove=false |
| 自动审批 | 凡裁决为 ask 的一律自动 APPROVE（主/子agent、前台/后台都不弹卡）；deny 裁决/plan 只读拦截照常拒绝 | sessionAutoApprove=true，模式覆盖清除 |
| 完全授权 | ask 自动 APPROVE + 模式覆盖 BYPASS（低危直通第9步放行）；**deny 规则/内容级 deny 仍拒绝**（免疫 bypass 的既有安全语义不动） | setSessionPermissionMode("full") + sessionAutoApprove=true |

- 会话内存态，**不落盘**；重启/新装默认回到"默认"。作用域=工作空间会话上下文（与"本会话允许"一致）。
- 与 G2 的交叠：后台非交互作用域内，**未授权 → 自动拒绝（G2 原样）**；
  **已选自动审批/完全授权 → 自动放行**（用户明示授权无人值守，子agent不再因确认被拒/挂起）。
- 自动审批返回 plain APPROVE——不触发 APPROVE_WHITELIST/APPROVE_SESSION 的写规则/全局放行副作用。

### 3.2 ConfirmGate（additive）

```java
private volatile boolean sessionAutoApprove;
public synchronized void setSessionAutoApprove(boolean on)   // + DiagnosticLog
public boolean sessionAutoApprove()
public synchronized void clearSessionPermissionMode()        // override=null; sessionBypass=false
// askUser/askUserDetailed 帮助方法内顺序：
// ① sessionAutoApprove → ALLOW session-auto 日志 + APPROVE（不调 ui）
// ② isNonInteractive() → DENY non-interactive 日志 + REJECT（G2 原样）
// ③ ui.ask / ui.askDetailed（前台现状逐字不变）
```
裁决顺序/规则引擎/敏感路径/权限模式默认分类不动；sessionBypass 旧语义不动
（v2 下 APPROVE_SESSION 后的残留 ask 仍弹卡——只有新选择器显式置 autoApprove 才免弹）。

### 3.3 SessionManager

```java
public String setConversationPermission(String choice)  // "default"|"auto"|"full"
// 取 currentSession.workspaceName → ctxByName → ctx.confirmGate；按 3.1 表设置；返回反馈文案
public String conversationPermission()                  // 由 gate 状态反推当前选择（GUI 同步）
```

### 3.4 GUI（MainWindow + InputView）

- InputView 新增 `setPermissionSelector(Node)`：bottomRow 发送键左侧插入（照 setModelSelector:320 先例）。
- MainWindow 装配 MenuButton（btn-ghost/model-picker 同款样式，文案"权限: 默认/自动审批/完全授权"），
  三个 MenuItem；点击 → manager.setConversationPermission → 更新按钮文案 + controller.onSystem 反馈。
  Tooltip 明示：仅本对话（工作空间会话）生效、不落盘、deny 规则仍拒绝。
- 会话切换处与 refreshModelMenu 同步刷新选择器文案（读 conversationPermission()）。
- 不改 Shift+Tab 全局循环（既有行为保留）。

## 4. 测试与验收

**新增**：
- `ConfirmGateSessionAutoApproveTest`：autoApprove 高危放行且 ui 零调用；deny 规则（会话 deny）
  在 autoApprove/完全授权下仍拒绝；plan 模式数据工作流拦截不受 autoApprove 影响；
  v2 ask 规则（免疫 bypass）在完全授权下自动放行；clear 恢复全局默认；
  非交互+未授权→REJECT（G2 保持）；非交互+autoApprove→APPROVE；新实例不持久化。
- `TaskStripModelTest` 扩展：running 子Agent徽标；无 running 文案不变。
**受影响旧套件（未经修改必须全绿）**：ConfirmGateTest、ConfirmGateNonInteractiveTest、
CommandWhitelistTest、GuiConfirmUiTest、PermissionDialogLogicTest、BackgroundChildAgentTest、
SubAgentPlanModeTest、TaskV2ToolsTest、ControlLoopGuardTest、TaskStateChildVisibilityTest、
TaskStripModelTest（扩展项除外）。
**全量回归**：安静窗口 clean test ≥2933 只增不减，0F/0E。
**人工验收**：选"自动审批"后主/子agent高危操作不再弹卡且日志 ALLOW session-auto；
选"完全授权"同理；切回"默认"恢复弹卡；重启后回到默认；TaskStrip 折叠态可见"子Agent N运行"。

## 5. 实施与交付顺序

1. H1 + 测试 → 定向 → 独立提交；
2. H2 + 测试 → 定向 → 独立提交；
3. 安静窗口全量回归 → 新基线写回本档/README/G 档 → docs 提交；
4. 打包 **2.4.8**（docs/update-package-version.txt 递增；包名/脚本/备份/校验同版本号）。
   注：本机无 2.4.7 基线 JAR（dist 最新为 v68 内容基线小更新包），小更新补丁无法对未知办公室基线制作；
   采用既有 **Update-Minion.cmd 整 JAR 更新流程**（校验 SHA-256→备份→替换→失败自动恢复→Rollback），
   对任意已安装基线生效，随包说明注明。

## 6. do-not-touch

ConfirmGate 13 步裁决顺序、deny 免疫 bypass、敏感路径、plan 只读拦截、GuiConfirmUi 前台无限等待、
G2 非交互默认拒绝、Shift+Tab 全局模式循环、APPROVE_SESSION 既有语义、子agent禁用工具防御、
看门狗 600s、child_wait ≤30s、TaskBoardModel 只读投影。

## 7. 实施状态（2026-11-25）

| 项 | 提交 | 内容 | 定向结果 |
|---|---|---|---|
| H1 | ffb6f3c | TaskStrip.summaryText 运行中子Agent徽标 + TaskStripModelTest 5 项 | 15 项全绿 |
| H2 | 207741d | ConfirmGate sessionAutoApprove/clearSessionPermissionMode + SessionManager.setConversationPermission/conversationPermission + InputView.setPermissionSelector + MainWindow 权限 MenuButton + ConfirmGateSessionAutoApproveTest 13 项 | 13 项全绿 |

实施核实细节：
- 定向合跑 151 项全绿（含 G1 收敛 4a1616b、受影响旧套件未经修改）。
- 任务条实时链路复核：MainWindow:251-256 对话框上方装配（默认开）；TaskStrip 1s Timeline 轮询
  TaskBoardModel.snapshot（todos+state.children 全集+后台任务）；追加待办/派发子agent ≤1s 上条；
  signature 排除输出尾部防抖。GUI 实机观感由用户验收（headless 仅锁定纯展示逻辑）。
- 全量回归基线：G+H 全部提交后安静窗口 clean test 定稿 **2972 项 0F/0E/40S/376 类**
  （较 E/F 基线 2933/373 净增 39 项 3 类，只增不减；2026-11-25 12:40）。