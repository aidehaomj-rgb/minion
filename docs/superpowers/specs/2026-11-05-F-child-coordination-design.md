# F 批次：可靠子任务协调与隔离交付设计

## 最初并行子任务边界（历史；后续宿主接线见文末）

仅修改 TaskTool、GitTool、OutputMerge，新增协调/隔离帮助类与测试。不修改 AgentLoop、TaskState、TaskWorkflow、SessionManager、总计划或 README。不新增依赖；Java 8；离线验证使用独立临时 javac 输出目录，不写共享 target。不 reset/clean/提交/打包。

## F01/F02/F04 控制面

- `ChildTaskCoordinator` 持有独立可序列化记录，不借子 Agent 总结改写 TaskState。每项必须有 `stepId/description/scope/dependsOn/acceptance`；scope 为工作区相对路径前缀，worker 写范围拒绝重复/父子重叠；explore 只读允许交叠。批量原子登记，支持依赖乱序，拒绝缺失依赖、自依赖与环。
- 状态区分 queued/running/returned_needs_review/reviewed/failed/cancelled/timed_out/interrupted_needs_review/merged_needs_validation/validated。结构化执行结果中的成功只能进入待复核。主 Agent 的宿主 API 复核必须校验证据文件和验收报告；子任务工具不暴露复核入口。依赖仅在主 Agent 复核通过后放行；合并型依赖须联合验证通过。
- 有界并发、队列和超时；取消传播到依赖，独立故障不停止无关项。不自动重放未知副作用；重试须宿主明确原因并先确认旧执行已结束。不能强杀 Java 线程：忽略中断的执行继续占用物理槽和范围租约，不以 Future.cancel 的完成假象释放写权限。取消回调必须非阻塞，宿主接 SubAgentLoop.interrupt/独立 client.cancel。
- snapshot/restore 提供 Gson DTO，重启时 running 不自动重跑，转人工复核；未确认旧进程静止前不得恢复写任务。服务端负载由宿主提供容量/暂停 API，不用字符串猜测负载。

## F03/F05 隔离与合并

- 新增 Git 隔离生命周期帮助类：记录仓库 HEAD、scope 文件基线（含用户未提交内容）、worktree 名称/路径/起点；复用无 shell 的 git worktree add --detach/remove。创建失败、超时、取消不自动清理可疑或 dirty worktree；清理只允许本次登记且干净、HEAD 未被改动的 worktree，永不 --force。
- 代码合并仅把隔离目录中的明确文件提交给 OutputMerge，禁止整棵树 checkout/merge/reset。合并计划绑定源/目标哈希、任务/子任务/输出根和预览身份；需要显式基线，不接受重预览来洗掉用户未提交变化。已有非 Git 文档流程保持显式人工预览兼容。
- 写前备份，写后记录 merged_needs_validation，不宣称最终完成。宿主联合测试后才可 validated。未接主流程的 API 只能称基础设施已实现。

## C05 只读 Git 审查快照（追加需求）

- GitTool 保持原构造器和 review 行为，新增只读 action=review_snapshot。返回 JSON：type=minion.git-review.v1、scope、files、diffHash、diffText、truncated；另含要求收窄的提示。默认 staged+unstaged+untracked，路径可过滤；排除 .git/.minion/node_modules/target/dist 等产物。
- diffHash=SHA-256(实际返回 diffText 的 UTF-8 字节)，不以 diff --check 假装语义审查；截断明确不可据此完整验收。二进制只标记不读内容；大小/文件数/总耗时有界；读取期间版本变化必须报错或 truncated，不能返回稳定快照假象。
- 主流程持有真实快照，另派 explore 审查 Read/Grep/Glob/Git 只读，要求文件/行号/风险/证据。此批不调用 LLM、不接主循环。

## 最初待接线清单（主流程后续已实施，见文末）

1. AgentLoop 当前注册 `new TaskTool(this)` 处改注入协调器派发适配器；旧字符串 runner 无结构化成功证据时 fail closed。
2. 主循环运行子任务的宿主边界注入结构化 Runner，复用现有父模型继承工厂（不得提供 override）；将实际 succeeded/recordPath/验收报告交给协调器。
3. 主 Agent review/依赖审批边界调用 reviewFromParent；输出合并完成调用 markMerged，联合测试回执调用 validateFromParent。
4. 会话保存/加载处保存/恢复协调器 Snapshot；interrupt/shutdown 处 cancelAll/close；Git 隔离辅助 API 由代码任务宿主显式调用。

## 验证计划

独立 JUnit/javac：38 分块、逆序依赖、并发峰值、重复/嵌套 scope、失败字符串不假成功、取消/超时后迟到返回、证据变更、持久化恢复、Git dirty 保留/基线变化/二进制/截断、合并计划篡改/重复应用/合并后待验证。最终记录实跑结果与未接线项目。

## 2026-11-05主流程宿主集成

- 已添加ChildTaskHost与结构化task兼容分支；AgentLoop真实执行、工具写前scope核对、单独客户端取消、惰性journal/恢复、生命周期关闭。旧TaskTool(this)同步description入口保留，不要求旧调用方突然改协议。
- SessionManager增加RootedRuntimeFactory，registry和Workspace均在真实隔离根创建；不是把主目录的工具拿去配一个worktree名字。scope内脏文件直接拒绝，其他脏文件不动，不复制过时HEAD来冒充当前代码。
- AgentControl增加child_status/wait/review/reject/cancel/retry/capacity/resume_queue；review需父任务返回后实际Read证据的成功ID，不接受子回执/状态查询。队列/运行结果不代表完成。
- 重试保留previousAttempts，旧childId/隔离目录/证据不被新尝试洗掉；current childSnapshot只显示当前修订和同一步骤最新尝试。迟到回执归属原taskId，不进入新任务。
- worktree结果经显式合并和真实父Build验证后，OutputMerge记录与协调器依赖验收接通；仍等待代码验证的排队依赖不会反向阻止父Build。
- 等待子结果时主模型不轮询；新修订/模型切换/停止传播取消。物理执行未退出仍阻止主写入，不因取消future假释放。
- 本机端到端测试CoordinatedChildHostTest覆盖惰性启动/模型override拒绝、真实文件写/依赖、越界和元数据拒绝、取消、修订、重试、恢复、实际worktree+Python断言+联合验收，以及父请求不空转。当前不是38个真实模型并行验收。
- 范围worker不能运行任意命令；由父Agent运行实际测试。这是Win7应用层可解释边界，不宣称实现操作系统隔离。合并后父修复的显式再核验仍待完善，旧目标哈希变化时保持阻断。
