# 批次 D：精准记忆与失效控制

## 范围与约束

用户已授权并行实现本批次。仅修改 ProjectMemoryStore、MemoryTool、HistoryArchive，新增本地索引及对应新测试。不改 AgentLoop / ContextManager / SessionManager / 总计划 / README，不运行 Maven，不打包、不提交、不覆盖旧产物。Java 8、内网 Win7，无新运行时依赖。

## 数据与生效规则

- Markdown 元数据扩展：supersedes、conflicts、conditions（taskId/pathPrefix/validFrom/validUntil）、aliases、sourcePaths、来源 SHA-256 指纹、disabled。旧方法保留，新增 Details/Scope 重载。
- 新建、修改、自动提取均为待核验候选；核验时捕获显式文件依赖（兼容来源为单个文件路径）。文件变化/缺失/越界或无法核对时，两条实际入口 verifiedIndex/search 都排除。原核验元数据不因检索而改写。
- 有条件但未提供所需上下文时不注入。已核验的替代关系在旧条目保留 retirement 记录，替代条目改坏、删除或关闭后不让旧决定复活；候选不能淘汰旧决定。替代适用范围保留在 retirement 中。
- 冲突双向识别；无明确替代解法时不注入冲突双方。编辑前保留 Markdown 修订快照，删除仍移到 trash，支持 disable/enable（启用须重核）。全局身份与偏好仍须当前真实用户人工核验，不加载跨项目历史。

## 检索

- 中文词/二字片段、NFKC、轻量别名归一、本地倒排 BM25；按需创建、有界进程内缓存、按内容/文件签名增量更新。启动不扫描项目树或会话库，不使用向量服务。
- 记忆按核验、适用范围、任务、路径、时间、去重过滤；引用提供 Markdown 路径与正文行号。历史只检索当前会话索引，重复片段合并，失效来源明确 changed 标记；可用新增严格检索重载排除失效来源。
- 不改已有历史 JSON、事件内容、事件哈希和索引哈希；本地检索索引为可丢弃派生状态。

## 验证与接线

使用独立 target/batch-d-* 目录 javac/JUnit，绝不与主流程 Maven 输出竞争。覆盖来源等长同 mtime 更新、缺失、替代与冲突、条件、删停、别名、增量、跨项目、历史哈希与重复、中文/数字查询。最终补录真实执行结果。主流程后续应将当前 taskId/path 传给 Scope 重载；当前旧入口保持兼容并对未知条件 fail-closed。真实内网模型、20 万 token / 连续 compact、Win7 真机及人工金标准评测不足不得声称完成。

## 实施结果与边界

| 项目 | 状态 |
|---|---|
| D01 替代、冲突、适用条件 | 已实现；候选不抑制已核验旧条目，冲突未解双方都不注入；已确认替代留 retirement，删除替代者不复活旧决定 |
| D02 轻量检索 | 已实现中文词/字/二字片段、NFKC、BM25、保守内置同义词、每条 aliases、任务/路径/时间过滤与按需重建 |
| D03 来源变化失效 | 已接入 verifiedIndex、search 和 list 回退路径；SHA-256 可检出等长、同 mtime 内容更新，保留原核验元数据与修订副本 |
| D04 去重与原文定位 | 已实现；项目返回 location/line，历史返回 messageId/eventId/taskId/offset；不同任务/文件的同文历史保留各自来源 |
| D05 管理入口 | 原入口/方法签名兼容；新增 configure、read(id)、delete 别名、disable/enable、global_configure/global_disable/global_enable/global_delete；自动候选始终待核验，关提取不影响现有记录管理 |
| D06 评测 | 合成与既有回归已覆盖；真实脱敏回放、人工检索金标准、实际 20 万 token 与连续 compact、Win7 真机 **未完成** |

补充实现约束：

- 构造器不读盘。项目仅在查询时重读本项目 Markdown 核验元数据，倒排词项按内容差异增量更新；不缓存来源有效性。历史仅在 search 时枚举当前会话 `*.idx.json`，按 size/mtime 增量装载，候选仍校验索引哈希和原始事件哈希。无项目树/全会话扫描，无向量服务。
- 缓存上限为 16 个项目/全局存储、8 个会话；缓存可随时丢弃，不更改既有历史索引格式或哈希。
- 文件依赖须通过 `source_paths` 提供；兼容 `source` 为单个明确文件路径。自然语言来源不会猜测关联文件。旧已核验、明确文件来源但无指纹的条目显示 `needs_reverify`，不会在首次读取时伪造当前基线。
- 单个依赖文件 50MiB 上限；超过上限的项目记忆不能完成文件核验。历史中没有完整哈希的大文件来源仅为 `unverified`，严格模式排除。准确检测同 size/mtime 修改的代价是查询时重算关联文件哈希；未声称无磁盘开销。
- 启用已关闭条目不会自动恢复核验。已确认替代如有条件而当前缺少任务/路径，宁可暂不注入旧条目。替代条件明确不适用或到期时，原有通用记忆可按原范围生效。
- 项目写操作用固定条纹锁防止同进程多个 Store 实例丢失 retirement。多进程并发未验证；跨条目核验不是数据库事务，写入中断可能使旧条目保持停用而新条目待核验，采取 fail-closed，不自行恢复旧决定。
- 保留既有单条历史索引 200 万字符的头尾上限；超出上限的中部原文仍可 read，但不保证被索引召回。未引入全量回填或自动提取任务结论。

## 主流程需要的接线（本批次未修改这些文件）

1. `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\agent\AgentLoop.java`：项目 recall 处将 `session.taskState.id` 和已知当前路径传入 `ProjectMemoryStore.Scope`，调用 `verifiedIndex(limit, scope)` / `search(query, limit, scope)`。原两参调用已会排除 changed/冲突/停用/条件未知条目，不需接线才生效。旧 `memory.list()` 决定回退也已使用有效 verified。引用展示可增加 `entry.location:entry.line`。不把旧摘要/任务成果自动送去核验。
2. `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\storage\SessionStore.java`：若需要历史严格模式/时间过滤，可新增转发 `HistoryArchive.search(query, limit, taskId, pathFilter, fromTime, untilTime, includeChanged)` 的重载。现有历史查询默认保留并标记 changed/unverified，兼容手工追溯；不要将这些结果当现行事实。严格模式仅接受 `currentEvidence=true`，也排除无可核验来源的普通历史文本。
3. `MemoryTool` 内项目 index/search 已传当前会话 taskId（或显式 task）和 path；search_history 已转发显式 task/path，时间及严格模式尚待上述 SessionStore 转发；since/until 目前只用于项目记忆筛选。旧 project.md/handoff.md 仅标记为未经核验记录，显式 task/path/time 过滤时不混入这些无范围元数据的旧笔记。
4. `ContextManager` / `SessionManager` 不需改旧方法签名，未动 150K 压缩默认、主/子模型、自动提取默认关闭及全局人工核验门禁。

主流程统一构建时，先做以下定向测试，再做全量 `mvn test`（本批次不执行 Maven，不 package）：

```text
-Dtest=ProjectMemoryStoreTest,ProjectMemoryPrecisionTest,LocalMemoryIndexTest,HistoryArchiveTest,HistoryPrecisionTest,MemoryToolGlobalTest,MemoryToolHistoryTest,MemoryToolTaskTest,MemoryToolPrecisionTest,AgentMemoryRecallTest,SessionEventJournalTest test
```

## 文件清单（绝对路径）

- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\context\ProjectMemoryStore.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\context\LocalMemoryIndex.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\tools\context\MemoryTool.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\main\java\com\minion\core\storage\HistoryArchive.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\context\ProjectMemoryPrecisionTest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\context\LocalMemoryIndexTest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\storage\HistoryPrecisionTest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\src\test\java\com\minion\core\tools\context\MemoryToolPrecisionTest.java`
- `C:\Users\59809\Documents\科室工作日常管理\minion\docs\superpowers\specs\2026-11-05-precise-memory-design.md`

## 隔离测试证据

- Zulu Java 8u222，独立 `javac -source 8 -target 8` 编译本批次源码、所需依赖源码和 11 个测试类；不依赖被主流程清理过的 target/classes。
- 定向测试共 68 项；包含本批次新测试和既有 ProjectMemoryStore、HistoryArchive、MemoryTool、AgentMemoryRecall、SessionEventJournal 回归。首次发现两项原文检索过宽回归后增加明确原文优先，另三项为独立测试 classpath 未含 config.properties，已通过加入 src/resource 修正测试启动参数；原始失败日志保留，不冒充一次通过。
- 字节码检查覆盖隔离目录 164 个 class，最高 major=52；这不是全项目最终打包检查，也不是 Win7 真机证据。
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\compile-final.log`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\compile-tests.args`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\junit-final.log`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\junit-initial.log`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\validation.json`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\class-majors.json`
- `C:\Users\59809\Documents\科室工作日常管理\minion\target\batch-d-precise-memory\source-sha256.tsv`：最终交接源码/新测试/spec 指纹。未提交、未打包、未覆盖 dist 或旧交付包。
