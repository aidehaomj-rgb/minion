# 子 agent 修订过滤不可见（G1）与后台确认挂起（G2）修复设计

日期：2026-11-25
状态：**方案已获用户确认（AskUserQuestion：按推荐方案修 G1+G2；修完打包 2.4.8）**。
基线：main 39b36d4（E/F 收尾后），全量回归权威基线 **2933 项 0F/0E/40S/373 类**。
症状来源：办公室 Win7 实机（部署包 minion-win7-offline-0.1.0 + 小更新包）——派发子 agent 后
"子Agent数量: 0"、任务面板看不到运行情况、主 agent 串行兜底被记无进展、child_wait 反复等待约 9 分钟。
minion 自检报告 4 点已逐条对照主源码核对（①等待机制没坏/崩溃有捕获；②写盘冲突无证据；
③方向对机制错=本档 G1；④预检日志为小改进），核对证据见会话记录，不赘述。

---

## 1. 根因（全部 HEAD 实测，非旧包专属）

### G1 运行中子 agent 被修订过滤"隐身"

- 用户每条实质补充 → `TaskState.addGuidance` 修订号 +1（TaskState.java:232-236；
  "继续/好的/确认"等短语不推进）。
- 子 agent 注册时烙当前修订号（:335 `c.revision=revision()`，状态 running :336）。
- `currentChildRefs()`（:430-437）只保留 `c.revision==null || c.revision==revision()` 的子 agent
  → **修订推进后，仍在跑的子 agent 被整体过滤**：
  - 实施时核实的语义细节：原谓词 `c.coordinatedStep==null || (修订匹配 && 最新attempt)` 中，
    **非协调子agent（普通 TaskTool startChild 路径）恒可见，不受修订过滤**；修订过滤只咬
    **协调型子agent**（ChildTaskHost.java:54 派发时设置 coordinatedStep/coordinatedAttempt，
    即办公场景的任务队列/分块派发路径）。G1 豁免因此精确作用于协调型 running 子agent。
  - "当前任务子Agent数量"（:584）= 0；
  - `childSnapshot()/childRows()`（:439/:443）→ 任务面板/TaskStrip 无行；
  - 任务上下文渲染（:585-590 子任务清单）→ **主 agent 也看不到自己在跑的子 agent** → 串行兜底 → 无进展。
- 子 agent 本身仍在执行，结果仍经 pendingControlNotices 回流——但 UI 与主 agent 上下文全程"失明"。
- "之前可以看到"：1d4424b 修复追问修订同步前，修订号不推进，过滤不咬人；修好后暴露本过滤缺陷。

### G2 后台子 agent 高危确认持全局锁无限等待

- 主/子 agent 共享 ConfirmGate（AgentLoop:1023 传入 SubAgentLoop）；`check` 为 **synchronized**
  （ConfirmGate.java:149）。
- 高危/越界/Hook 确认走 `ui.ask/askDetailed`（:116/:129/:209/:325/:360/:371）→
  GuiConfirmUi `q.take()` **无限等待用户点击**（GuiConfirmUi.java:43-61，设计如此：前台点击即送达）。
- 后台子 agent 弹卡时用户往往不知道要点（卡片无子 agent 标识）→ 子 agent **持着 ConfirmGate
  监视器锁挂到 600s 看门狗**（AgentLoop:767-785）；期间**主 agent 的任何工具确认也被锁堵死**。
- 与 G1 叠加：子 agent 挂起 + 不可见 + 主 agent 卡死 = 办公室全部症状。

## 2. 修复设计（additive，前台语义零变化）

### G1 运行中子 agent 不受修订过滤（TaskState.currentChildRefs）

```java
// 谓词改为：最新attempt去重逐字保留；running 仅豁免修订过滤
for(Child c:children) if(id!=null&&id.equals(c.taskId)
    &&(c.coordinatedStep==null
       ||(latest.getOrDefault(c.coordinatedStep,0)==(c.coordinatedAttempt==null?1:c.coordinatedAttempt)
          &&("running".equals(c.status)||c.revision==null||c.revision==revision())))) result.add(c);
```
- 只豁免 **running** 的修订过滤（唯一真活跃态；returned_needs_review/failed_or_interrupted/reviewed/
  cancelled/interrupted_needs_review 均属已结束/待复核，维持旧过滤——历史修订结果不冒充当前）。
- latest-attempt 去重**逐字保留**（旧特征化测试 TaskStateChildProjectionTest 要求同步双 running
  仅最新 attempt 可见）；旧修订 running 子agent计入 latest map（否则豁免被 map 自身的修订过滤抵消）。
- 排序（:582）不动：running 天然排前。
- **不改** addGuidance/修订推进语义、finishChild/review 状态机、证据通道。

### G2 后台子 agent 非交互确认（ConfirmGate ThreadLocal 作用域 + 可操作拒绝文案）

```java
// ConfirmGate additive：
private static final ThreadLocal<Boolean> NON_INTERACTIVE = new ThreadLocal<>();
public static <T> T nonInteractive(java.util.function.Supplier<T> body) {
    NON_INTERACTIVE.set(true); try { return body.get(); } finally { NON_INTERACTIVE.remove(); }
}
public static boolean isNonInteractive() { return Boolean.TRUE.equals(NON_INTERACTIVE.get()); }
// 6 个 ui.ask/askDetailed 调用点机械改经私有 askUser/askUserDetailed 帮助方法：
// isNonInteractive() → DiagnosticLog.info("permission","DENY non-interactive …") + REJECT（不调 ui、不等待）
```
- **作用域仅包后台执行**：AgentLoop.runBackgroundChild 的 `executeChild(child,false)` 调用包进
  `ConfirmGate.nonInteractive(() -> executeChild(child,false))`。前台同步子 agent、主 agent、
  resume 路径**不包**→ 行为逐字不变。
- ThreadLocal 随子 agent 执行线程，不污染池内其他任务（finally remove）。
- SubAgentLoop.runOneTool 拒绝文案（:720-722）按 `ConfirmGate.isNonInteractive()` 分流：
  后台 → `"后台子Agent无法交互确认，高危操作已自动拒绝（X）；请改用只读替代，或把该操作写入报告交主Agent前台执行"`；
  前台 → 原文案 `"用户拒绝了该操作（X）"` 逐字保留。
- 效果：后台子 agent 永不进 q.take() → **不再持锁等待** → 主 agent 确认链路解堵；
  子 agent 拿到可操作错误继续推理/报告，不再挂 600s。
- **不改** ConfirmGate 裁决顺序/规则引擎/敏感路径/权限模式；synchronized 本身不动
  （后台不再阻塞等待后，锁持有时间回到纯裁决量级）。

### 顺手小项：派发预检日志（自检报告④的合理部分）

runBackgroundChild 入队执行时记 `DiagnosticLog.info("child-dispatch", childId/mode/revision/assignment 摘要)`；
G1 豁免可见时记 `child-visibility`（running 旧修订被豁免的条目）。纯 additive。

## 3. 测试与验收

**新增**：
- `TaskStateChildVisibilityTest`：rev1 派发 running → addGuidance 推进 rev2 → childSnapshot/childRows/
  数量渲染仍含该子 agent；finishChild 后（returned_needs_review@rev1）恢复旧过滤不可见；
  reviewed/cancelled 旧修订仍过滤；最新 attempt 去重不变（含修订推进后同步双 running 仅最新可见）；
  "继续"短语不推进修订（特征化）。
- `ConfirmGateNonInteractiveTest`（FakeConfirmUi 计数）：nonInteractive 作用域内高危/越界/Hook 确认
  → REJECT 且 **ui 零调用**、permission DENY 日志；作用域外 → ui 照常调用（特征化）；
  嵌套/异常路径 finally 清理（同线程后续前台确认不受污染）。
- BackgroundChildAgentTest 扩展：后台子 agent 高危工具 → 可操作拒绝文案断言 + 主 agent 确认不被堵
  （fake ui 延迟应答下主线程 check 及时返回）。

**受影响旧套件（未经修改必须全绿）**：ConfirmGateTest、CommandWhitelistTest、GuiConfirmUiTest、
PermissionDialogLogicTest、BackgroundChildAgentTest、SubAgentPlanModeTest、TaskV2ToolsTest、
ControlLoopGuardTest、TaskToolArgumentsTest、AgentLoopArtifactsTest、LateAdoptTest、
TaskStateChildProjectionTest（attempt 去重特征化）。
**全量回归**：安静窗口 clean test ≥2933 只增不减，0F/0E。

**验收**：
- 复现脚本：派发后台子 agent → 用户发实质补充 → 任务面板/数量/上下文仍见 running 子 agent（G1）；
  后台子 agent 跑高危命令 → 自动拒绝+报告，主 agent 确认卡正常弹出可点（G2）。
- 前台同步子 agent 与主 agent 确认行为特征化逐字不变。
- 办公室生效前提：安装 2.4.8 更新包（含 bf0308c 以来全部修复 + E/F 批 + 本修复）。

## 4. 实施与交付顺序

1. G1 + 测试 → 定向 → 独立提交；
2. G2 + 预检日志 + 测试 → 定向 → 独立提交；
3. 安静窗口全量回归 → 定稿新基线写回本档/README；
4. 按 docs/更新包版本规则.md 递增 **2.4.8**，走 tools/win7-updater 既有小更新包流程制作
   （包名/安装脚本/备份/校验信息同版本号；基线=上一已发布版本 2.4.7）；
5. 用户拷贝办公室安装验证（实机验收由用户执行）。

## 5. do-not-touch

ConfirmGate 裁决顺序/规则引擎/敏感路径/权限模式默认分类、GuiConfirmUi 前台"点击即送达无限等待"
语义（仅后台作用域豁免）、addGuidance 修订推进语义、finishChild/review 状态机、证据通道与门禁、
子 agent 禁用 AskUserQuestion/Skill/task 递归防御、看门狗 600s、child_wait ≤30s 预算。

## 6. 实施状态（2026-11-25）

| 项 | 提交 | 内容 | 定向结果 |
|---|---|---|---|
| G1 | 6c55283 | TaskState.currentChildRefs running 豁免 + addGuidance child-visibility 日志 + TaskStateChildVisibilityTest 7 项 | 33 项全绿 |
| G1收敛 | 4a1616b | 全量回归暴露旧特征化测试 TaskStateChildProjectionTest 失败（同步双running应仅最新attempt可见）→ running 仅豁免修订过滤、attempt 去重逐字保留、latest map 计入旧修订 running；+组合语义测试 1 项 | 定向 151 项全绿（含旧套件未经修改） |
| G2 | eb30e73 | ConfirmGate ThreadLocal 非交互作用域 + 6 询问点 askUser/askUserDetailed + runBackgroundChild 包裹/child-dispatch 预检日志 + SubAgentLoop 后台拒绝文案 + ConfirmGateNonInteractiveTest 12 项 + BackgroundChildAgentTest 高危自动拒绝行为项 | 120 项全绿（受影响旧套件未经修改全绿） |

实施核实细节：
- 修订过滤只作用于**协调型**子agent（ChildTaskHost.java:54 设置 coordinatedStep）；
  非协调子agent（普通 startChild）恒可见的旧语义保持不变，测试已特征化。
- G2 作用域内拒绝优先于 ui；**H2 对话级自动审批/完全授权显式开启后优先于非交互拒绝**
  （用户明示授权无人值守，见 H 档 §3.1）——未授权时 G2 默认拒绝逐字不变。
- 打包调整：本机无 2.4.7 基线 JAR，2.4.8 改用既有 Update-Minion.cmd **整 JAR 更新流程**
  （对任意已安装基线生效），见 H 档 §5 与 tools/win7-updater/package-2.4.8-full.py。
- 全量回归基线：G+H 全部提交后安静窗口 clean test 定稿 **2972 项 0F/0E/40S/376 类**
  （较 E/F 基线 2933/373 净增 39 项 3 类，只增不减；2026-11-25 12:40）。