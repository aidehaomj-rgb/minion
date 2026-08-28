# Win7 Agent 全功能完善设计

## 1. 目标与边界

本轮把 2026-08-28 能力盘点中的 21 项适用功能补入桌面版 Minion，运行约束为 Windows 7、JDK 8、Python 3.7.6/Anaconda3、内网离线、Qwen3 27B OpenAI Chat Completions 兼容接口。

不纳入本轮：OpenAI/ChatGPT 账号体系、多用户 Web 平台、依赖新版 Node/Playwright 的方案、原生 Android/iOS 完整构建环境、强依赖本地大模型的向量库。桌面应用和 EXE 构建仍纳入。

## 2. 总体方案

功能按“核心服务 + Agent 工具 + 本地命令 + 必要 GUI”实现：

- 核心服务承担可测试的状态、存储、安全与恢复逻辑。
- Agent 工具提供给 Qwen 调用，schema 保持扁平、枚举明确、错误可自恢复。
- 斜杠命令提供用户可直接控制的诊断、队列、日志、上下文等入口。
- 仅需要持续交互的功能进入 JavaFX 设置窗或消息区，避免 Win7 JavaFX 复杂度和性能回归。
- 所有新数据位于 jar 同目录 `.minion/` 或工作区 `.minion/`，升级不覆盖用户数据。

## 3. 功能模块

### 3.1 稳定性与安全

1. Diagnostics：Java/Python/Office 库/Chrome/Git/Node/模型配置诊断，输出脱敏报告。
2. QwenCompat：工具参数 JSON 容错、参数校验错误反馈、连续失败提示与简化 schema。
3. Checkpoint：Write/Edit 和结构化办公写入前建立检查点；list/restore/clear。
4. RunQueue：运行中普通输入进入 FIFO 队列，支持 list/remove/clear；Steering 仍保留立即补充语义。
5. DiagnosticLog：滚动日志、工具耗时、错误脱敏、导出支持包。
6. UpdateManager：版本/更新包检查、SHA-256、备份、回滚状态；继续兼容 Win7 CMD 更新器。

### 3.2 办公与数据

7. Excel：inspect/read/filter/profile/write/append/merge/pivot/chart/export，默认输出新文件。
8. Document：DOCX/PPTX/PDF 提取、生成、替换、PDF 合并拆分和 OCR 依赖诊断。
9. Database：SQLite、ODBC/SQL Server、MySQL、PostgreSQL 连接诊断、schema/query/execute/export；默认只读。
10. Knowledge：文件夹批量导入、增量更新、来源/页码、关键词评分和引用返回。
11. Attachment：拖放、剪贴板文件、预览状态、大文件分段和临时附件清理。

### 3.3 开发工作台

12. Git：status/diff/log/branch/stage/unstage/commit/pull/merge；写操作确认。
13. Files：tree/search/read_chunk/metadata，GUI 编辑继续复用外部 VS Code，避免内置编辑器破坏编码。
14. Process：后台进程 start/list/output/stop/clean，按会话和工作区隔离。
15. Project：Python/Java/Web/Flask/SQLite/PyInstaller 模板初始化。
16. Build：项目识别、test/build/artifacts/serve，产物归集到 dist。

### 3.4 高级 Agent 与体验

17. Browser：tabs、wait_selector、upload、downloads、network_idle、分页提取、人工接管。
18. Session：search/pin/archive/fork/export/import；保持旧 JSON 兼容。
19. Context：构成统计、输出策略、文件摘要缓存、项目记忆、handoff 摘要。
20. Permission/Secrets：权限模式、会话授权、审计日志、敏感信息脱敏；凭据本轮采用 Windows 用户隔离文件 + 最小明文暴露，DPAPI 在无 JNA 条件下使用系统 PowerShell 能力并提供不可用降级。
21. Skills Admin：list/read/create/update/enable/disable/import/validate，外部 Skill 覆盖内置 Skill 的既有规则不变。

## 4. 数据目录

```
<jarDir>/.minion/
  diagnostics/
  logs/
  permissions/
  processes/
  updates/
<workspace>/.minion/
  checkpoints/<sessionId>/
  knowledge/
  memory/
  cache/
```

## 5. 安全规则

- 所有工作区路径走 PathsGuard，并校验符号链接/Junction 的真实路径。
- SQL 写入、Git 改写、后台进程、文件恢复、构建发布均标记为高风险或按操作分类确认。
- 诊断与日志对 apiKey/token/password/Authorization/连接密码脱敏。
- Office/数据库修改默认另存为新文件，显式 overwrite 才覆盖且必须先建检查点。
- 工具输出继续使用会话临时目录落盘并遵循保留期。

## 6. 兼容性

- Java 代码只使用 JDK 8 语法和 API。
- Python 脚本兼容 Python 3.7.6，不使用 match、海象表达式、新版 pandas 专属 API。
- 可选依赖必须先诊断；缺失时返回离线安装包名称和兼容版本，不使 Agent 启动失败。
- 不引入要求 Java 11+ 或 Windows 10+ 的依赖。

## 7. 验收

- 每个新核心服务/工具有 JUnit4 测试；Python集成测试可注入解释器。
- 既有全部测试通过。
- `/capabilities` 和 README 覆盖新增入口。
- GitHub main 最终合并后再次全量测试。
- 生成一个包含本日全部改动、SHA-256、备份和回滚能力的 custom.3 增量更新包。
