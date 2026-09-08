# Win7 离线 Agent 能力底座设计

## 背景与目标

运行环境固定为 Windows 7、JDK 8、Anaconda Python 3.7.6，模型为内网 Qwen 27B。目标是在不接入 OpenAI 专属 API、不依赖公网服务的前提下，补齐浏览器操作、Excel 数据分析、SQLite、本地知识库、网页/代码开发、EXE 与 App 源码开发的基础能力。

## 现有能力与缺口

现有 Read/Write/Edit/Glob/Grep/Bash 已覆盖代码开发和构建的通用底座，CDP 浏览器、Skills、MCP、子 Agent、权限确认和上下文压缩也已存在。缺口是：

1. 无法稳定发现内网 Anaconda，模型只能猜测 `python` 命令。
2. Excel、SQLite 和知识库没有低参数量的专用工具，27B 模型需要反复生成脚本。
3. 浏览器输入、点击和表格提取主要依靠原始 JavaScript，调用难度偏高。
4. 缺少针对离线数据、开发和打包场景的内置工作流说明。

## 设计

### PythonRuntime / Python 工具

- 发现顺序：`python.path` → `MINION_PYTHON` → `CONDA_PREFIX` → 用户目录 Anaconda/Miniconda → `C:\ProgramData\Anaconda3`/`C:\Anaconda3` → PATH。
- 不激活 Conda，直接调用目标 `python.exe`，避免 Win7 shell 和环境继承差异。
- 固定 UTF-8 输出，支持状态诊断、临时代码和工作区脚本；超时后终止。
- Python 脚本执行限制在工作区，危险代码特征进入现有确认链。

### Excel / SQLite

- Excel 通过现有 pandas/openpyxl/xlrd 读取，不在 Agent 中复制完整表格引擎；提供 inspect/read 两个稳定动作，复杂分析回到 Python 工具。
- SQLite 使用 Python 3 标准库，无 JDBC 新依赖；query 强制只读前缀，execute 进入高危确认。

### Knowledge

- 每个项目存储在 `.minion/knowledge/*.md`，便于备份、Git 管理和人工审阅。
- add/search/list/read/delete 五个动作；中文二元组与普通关键词评分，不要求嵌入模型或联网服务。
- 删除进入高危确认；文件名规范化并阻断目录穿越。

### 浏览器与技能

- 保留 BrowserEval 高级入口，新增 BrowserAction 的 click/type/select/text/table/links/scroll/wait 语义动作。
- 内置 7 个技能，仅注入名称与描述，正文按需加载；外部同名技能覆盖内置技能。
- 技能明确 Win7/Python3.7 版本边界：PyInstaller 5.13.x、PWA 可本机完成；现代 Android 构建需较新构建机，iOS 需 macOS。

## 安全与兼容

- 所有新增代码以 Java 8 编译，不使用 JDK 9+ API。
- 数据库和 Python 文件路径复用工作区真实路径守卫。
- 不增加 OpenAI Provider、账号、云文件搜索或向量服务。
- 不自动安装 Python 包；`Python status` 只报告内网已有模块。

## 验证

- 单元测试覆盖解释器发现、中文输出、Excel 真实写读、SQLite 建表查询、知识库中文检索、内置技能和斜杠命令。
- 完整 Maven 测试覆盖原有模型、会话、MCP、浏览器、权限、附件和工具链。
