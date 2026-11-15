# ZCode 源码对照审查：Minion V46 后续优化

审查日期：2026-11-05。本文是静态源码审查与开发建议，不是已实现功能或实机验收报告。

后续开发（2026-11-06）：已在此审计基础上实现 V47 开发版核心优化，详见 [开发实现与验收边界](V47-ZCode参考优化-开发与验收.md)。下文“本轮不改代码”和缺口描述均是审计当时的基线，不代表后续开发状态；MaxCompute、真实模型及 Win7 验收仍需单独完成。

## 1. 对照范围与证据边界

- 官方项目：[zai-org/ZCode](https://github.com/zai-org/ZCode)。已下载稀疏源码快照，未运行第三方代码、安装依赖或调用其模型服务。
- 锁定提交：`a9fe401eede6e6e5e01be9048679c206fe6f02d9`，提交日期 2026-11-03，说明 `chore: sync ZCode source v3.17.2`。
- 本地参考目录：`target/zcode-reference-20261105`，重点读取 CLI 的 core/contracts/bootstrap；不宣称通读整个仓库。
- Minion 对照的是当前本地 V46 源码。原有未提交修改保持不动，本轮不改运行代码、不构建新版本、不更新现有安装或已交付 ZIP、不推送 GitHub。
- 建议借鉴控制结构与验证方法，在 Java8/JavaFX8 上实现；不把 ZCode 的 Node/Electron 运行时引入 Win7。
- 本文中的收益是待验证的工程判断，没有测量前不宣称提速比例、记忆召回率或已达到同等能力。

## 2. 不应当重新开发的能力

Minion 已有结构化摘要、不可变会话事件、taskId/revision、当前指令优先、中文关键词索引、项目/全局记忆边界、结束核验、续接预算、异步子任务、worktree与父验收、按需工具路由、usage校准。

相关实现包括 `ContextManager`、`StructuredCheckpoint`、`SessionEventJournal`、`TaskState`、`StopGate`、`ContinuationBudget`、`LocalMemoryIndex`、`ProjectMemoryStore`、`ChildTaskCoordinator`、`ToolSchemaRouter`。后续重点是补齐接线、降低常驻开销和改善故障恢复，不能把这些现有能力重新列为“完全没有”。

## 3. 优先事项

### P0-A：统一长任务状态与外部等待，不靠模型轮询

**ZCode 参考**：

- [TurnMachine 状态迁移](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/agent/turn-machine.ts)。
- [运行任务注册表、终态等待与 branchGeneration](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/runtime-task/registry.ts)。

**Minion 现状**：子 Agent 已有协调与等待机制，不能说没有异步执行。但 `ProcessTool.java:28,102` 的进程记录是内存 Map，退出回收；数据库 `DbExecutor.java:30,84` 使用固定300秒查询超时。计划任务 `LocalJobs` 不是统一的外部作业控制器。

**建议**：抽出统一运行任务接口，将子 Agent、后台构建、浏览器下载、SQL/MaxCompute 作业投影为 queued/running/waiting_external/waiting_user/verifying/completed/failed/cancelled/unknown。保存 jobId、taskId、revision、attempt、外部实例ID、结果路径与取消状态。等待外部作业时释放模型和不相关资源，不消耗“无进展续接”预算；完成事件再唤醒主任务。新要求改变后，旧revision迟到结果只归档，不驱动新任务。

**重要边界**：ZCode 此处的具体注册表也是 `InMemoryRuntimeTaskRegistry`，不能据此声称它自动具备所有跨重启恢复。Minion 的磁盘作业账本、MaxCompute实例查询与取消是需要自行实现的扩展；普通本地子进程不能仅凭PID盲目重连或重放。

**验收**：模拟排队20分钟、断网后实例仍执行、客户端重启、用户改要求、取消后迟到成功；无重复提交、无假完成、无旧任务复活。MaxCompute还需实际服务验证，ZCode源码不等于有现成MaxCompute适配。

### P0-B：续接预算从固定窗口改为“阶段进展＋用户预算”

**已核实**：`ContinuationBudget.java:9,11,18,35` 有24窗口上限和512条seen上限；seen满后 `observe` 直接返回，不再推进 evidenceVersion。这不是已经复现的用户故障，但在超长任务中是应当覆盖的明确边界。`TaskState` 仍有旧版3次续接兼容分支。

**建议**：保留无进展熔断，不做无限循环；将“当前有效步骤有新进展”“等待外部作业”“用户总token/时长预算”分别计量。去重集合采用有界热索引和可核对的历史操作记录，不能简单清空seen后让旧操作再次刷进度。历史完整证据保留在事件记录，热集合容量不能冻结真实进展。

**验收**：超过512次不同有效回执仍能识别新进展；重复读取/换UUID/纯状态查询不能刷新预算；实际达到用户预算后明确暂停，不显示完成。24窗口不等于24轮模型调用，界面与文档必须区分。

### P0-C：压缩后恢复“当前工作集”，减少再次找旧指令

**ZCode 参考**：

- [按消息ID锚定保留尾段](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/runtime/helpers/compact-preservation.ts)。
- [压缩后有界恢复文件](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/runtime/helpers/compact-post-reminders.ts)。
- [分组清理旧工具正文的 microcompact](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/compact/microcompact.ts)。

**Minion 现状**：`ContextManager.prepareForRequest` 已做旧内容截断、相同工具输出去重和图片收缩；结构化摘要与最新任务交接也已存在，不是从零补“微压缩”。但普通截断仍以首尾字符为主；`ContextManager.java:448` 会持续保留pinned技能。`AgentLoop.java:1120` 的压缩交接作为合成USER消息进入请求；`Message.isUserInstruction` 对旧数据仍有文本前缀兼容判定。

**建议**：

1. 给模型输入中的资料显式标记来源：real_user、task_control、compact_summary、tool_evidence、skill、history_recall；本地以类型和revision判断，不再扩展更多前缀猜测。
2. 摘要之外单独保留当前工作集：活动stepId、未完成验收项、最新指令eventId、当前产物版本、下一步以及少量正在修改的文件范围。
3. 压缩后仅恢复当前步骤需要的文件片段；磁盘文件改变必须标记新版本，不能拿重读后的内容冒充历史Read快照。不全盘读取、不恢复旧任务。
4. 旧大工具输出变成事件ID/文件路径/范围引用；保留未解决错误和当前工具链，不能机械清空关键证据。
5. 技能正文按当前任务保留、到期卸载或重新按需加载；不把使用过的技能永久堆进长会话。

**阈值约束**：保持用户指定的150000 token完整摘要触发目标。去重/工作集投影与完整摘要分别统计、分别显示，不暗中把自动摘要降到80K/120K。真实服务端超限的应急压缩要说明原因。

**验收**：用户在长对话后改变要求；连续多次compact；技能切换；文件在压缩后被用户修改；38个分块中只剩最后一块未完成。核查下一工具动作是否针对当前任务，并能定位原文证据。

### P1-D：解除“一刀切串行”，按模型能力与动作副作用调度

**ZCode 参考**：[工具调度器](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/tool/scheduler.ts)显式使用依赖、readOnly、concurrentSafe和sideEffectScope。

**已核实**：Minion `SystemPromptBuilder.java:40` 仍要求每轮最多一个工具调用，Write分块不超过1000字符；但 `AgentLoop.java:1556` 后已经可以接收和提交多个tool_calls。这是已有执行能力与提示策略的明显不匹配。限制原本用于防止模型JSON截断，不能不分模型直接删掉。

**建议**：按实际能力探测启用小批量只读并行，初期2–4个；依赖读写分阶段，不将“先读后改”同时提交。大文件Write按输出预算和协议能力分块，不固定每1000字符就多调用一次模型。未知副作用继续串行，独立文件写入仍需冲突检查。ZCode默认并发值不直接照搬到Win7办公机。

**同时修正提示负担**：SystemPromptBuilder第2/3条对不明确要求及方案审查比较保守。后续区分真正阻塞的信息缺失与已授权范围内可采用的合理默认值；不要让每一份内部计划都触发一次人工审批。保留真实授权、破坏性操作和重大范围变更的确认。

**验收**：同一模型同一38分块任务记录模型调用数、工具耗时、总token、错误率；低能力模型自动退回单调用，不以取消确认或冲突检查换速度。

### P1-E：把资源锁缩小到项目/文件/浏览器会话

**已核实**：`ToolExecutionGuard.java:15–16,80–90` 使用进程级FILE_LOCK和BROWSER_LOCK。单路径Read/Write/Edit已有路径锁，部分网络/浏览器等待也已避开文件锁，不能笼统说所有写入完全串行。但未知Shell及部分复杂写工具仍会占用全局写锁，浏览器也跨会话共用一个锁。

**建议**：增加工具资源声明。确定输出路径的Excel/文档操作使用对应资源锁；不同项目不互相阻塞。不可证明范围的Shell保持保守独占。浏览器先明确独立profile/实例或共享实例租约，再按会话锁定，绝不能直接去掉共享锁让两个任务争抢同一标签页。

**验收**：项目A运行慢查询时项目B能读取、分析独立Excel；同一工作簿并发写仍被阻止；同一浏览器页面不交错点击。量化锁等待耗时，而不是仅看线程数。

### P1-F：稳定提示前缀与动态任务信息分离

**ZCode 参考**：[ContextBuilder](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/context/builder.ts)把stable/dynamic上下文和缓存提示分开。

**已核实**：Minion `AgentLoop.java:1106` 每次将完整system提示与 `taskState.render(todos)` 拼在一起；任务状态变化会改变开头的system内容。

**建议**：稳定规则/稳定工具元信息作为前缀，动态任务快照单独构造并放在合适位置；在不违反DeepSeek/Qwen接口消息约束的前提下保持前缀稳定。不要默认内网网关识别某家provider的cacheControl字段；只在明确支持时使用，其他情况靠稳定序列化及schema顺序减少变化。技能目录与已发现工具列表做增量公告，避免重复铺满。

**验收**：分别统计预填充/首token延迟、实际缓存usage（若提供）及总token；网关不提供缓存统计就标记unknown，不能声称必然命中或虚报提速。

### P1-G：记忆二阶段召回与来源校验缓存

**Minion 现状**：中文BM25、去重、索引缓存和来源失效已有实现。`AgentLoop.java:480` 将检索query截到前180字符；重要实体在长要求末尾可能未参与召回。`ProjectMemoryStore.java:511–538` 对未发生大小/时间变化的来源仍读取全文件hash；多个条目引用同一大文件时可能重复I/O。

**建议**：从当前目标/最新纠正/活动步骤抽取多个短query，再按task/revision/路径/来源有效性筛选；必要时才让同一模型对少量候选重排，低置信度不注入。来源hash按规范路径与文件身份共享本轮结果、异步失效通知；不能仅用mtime+size永久放行，否则同秒同长度改文件会复活旧结论。不同项目仍隔离，跨项目只共享用户明确同意的身份偏好/知识库。

**不要照搬ZCode实验分支**：[project-memory-retrieval-branch.ts](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/core/src/memory/project-memory-retrieval-branch.ts)在此次提交中显式关闭semantic-recall，默认走default-index；存在selector源码不代表默认启用。该selector还有空白字符资格判断，中文无空格输入需要单独评估，不能照抄英文召回策略。

**验收**：关键限制放在长要求末尾；无空格中文纠正；同秒同长度改来源；千条记忆关联同一大文件；跨项目负样本。记录召回准确率、额外token、磁盘读取量和检索耗时。

### P1-H：故障回放与分层性能账本

已有离线回放汇总器和八批次验收文档，下一步应让运行事件自动关联taskId/revision/attempt/eventId，记录模型等待、压缩、检索、工具执行、锁等待、外部作业等待与验证耗时。区分模拟、开发机、真实模型、Win7四种证据。

将本次P0边界加入回放集：超过512证据、慢查询不重提、compact后新要求、旧worker迟到、verifier坏JSON、模型流断开后工具副作用不明。先建立基线，再对比增量优化；不以增加更多检查/更多模型审查当作可靠性提升的唯一手段。

## 4. 不建议借鉴的行为

1. **核验失败默认完成**：此提交 [contracts/src/tools/target.ts:290–332](https://github.com/zai-org/ZCode/blob/a9fe401eede6e6e5e01be9048679c206fe6f02d9/apps/zcode-cli/packages/contracts/src/tools/target.ts#L290-L332) 的 `failOpenGoalCompletionVerification` 返回 `passed:true`，部分格式或基础设施错误会走该兜底。对“不能误结束”的需求不合适；Minion应保持unknown与completed分开，有界重试、必要时明确暂停，而非虚报完成。
2. **把可用类当默认能力**：semantic-recall默认关闭；后台注册表是内存结构；开源CLI指南还明确Computer Use为占位实现。不能宣称直接复制就有完整跨重启后台任务、语义记忆或原生Office操作。
3. **直接替换Win7运行时**：官方README开发栈要求Node24.14与现代Electron相关组件。只迁移设计，不将其依赖链放入当前离线小更新。
4. **以无限续跑代替完成判断**：正常等待、有效执行、重复失败和需要用户输入必须分开。用户明确停止必须生效，不能为追求“永不停”而重启已经取消的任务。

## 5. 建议实施顺序

1. **第一批：稳定性**。P0-A/B先落地，P0-C补类型化来源与当前工作集；不改变150K默认值，不更换模型。先用模拟外部作业验证，再接真实MaxCompute。
2. **第二批：效率**。P1-D/E/F，按能力启用批量工具、缩小确定范围的锁、稳定前缀；对照基线量化。
3. **第三批：记忆与验收**。P1-G/H、技能有界常驻、中文长对话金标准回放，最后做Win7及真实模型服务验收。

总体结论：优先减少模型不必要的决策/等待和历史干扰，将任务控制从提示语逐渐转为可验证的程序状态；不推倒重写，不引入新云服务，不靠持续堆规则把小模型上下文挤满。
