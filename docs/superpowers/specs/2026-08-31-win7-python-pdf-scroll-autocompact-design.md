# Win7 Python/PDF、直达底部与自动压缩设计

## 目标

在不改变 Win7、JDK 8、Anaconda Python 3.7.6 和 Qwen OpenAI 兼容接口约束的前提下：修复 Conda 的 SQLite DLL 搜索路径；补齐 PyPDF2/pdfminer 离线依赖与检测；增加聊天直达最新消息按钮；把上下文压缩改成主动、可见且保留近期任务状态的自动流程。

## 设计

1. `PythonRuntime` 启动子进程时，从配置的 python.exe 推导 Conda 根目录，并把根目录、`DLLs`、`Library\\bin`、`Scripts` 等已存在目录前置到子进程 PATH。诊断必须实际 import sqlite3/PyPDF2/pdfminer，而非只查模块描述。
2. Java 内置 PDFBox 继续作为无需 Python 的 PDF 文本解析兜底；Python 侧提供兼容 3.7 的离线 wheel 清单和安装脚本，供数据分析代码使用。诊断结果明确区分 Java PDF 与 Python PDF 模块。
3. ChatView 在滚动区右下角叠放“↓ 最新对话”按钮。用户离开底部时显示，点击后恢复跟随并滚动到底；流式输出期间不强行抢回用户滚动位置。
4. 自动压缩除比例阈值外预留输出和工具调用余量，在每次模型请求前判断。压缩期间显示状态，成功后给出压缩前后 token 数；保留系统提示、最近消息、置顶/摘要任务状态，防止连续无效压缩。
5. 所有代码保持 Java 8 兼容，并补充单元测试和 README 使用说明。

## 验收

- Conda Python 3.7 子进程 PATH 包含 `Library\\bin`，sqlite3 导入失败时输出具体缺失位置和修复建议。
- `/diagnostics` 分别显示 SQLite、PyPDF2、pdfminer、Java PDFBox 状态。
- PDF 文本附件即使没有 Python PDF 包也能由 PDFBox 解析。
- 聊天滚离底部可见按钮，点击回到底部并继续跟随流式消息。
- 上下文达到安全线后自动压缩且不会每一轮反复压缩。
