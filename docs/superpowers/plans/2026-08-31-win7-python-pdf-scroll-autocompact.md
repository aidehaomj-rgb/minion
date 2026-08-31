# 实施计划

1. 获取并合并 `upstream/main`，逐项解决与本地功能冲突。
2. 加强 PythonRuntime 的 Conda DLL 环境组装和模块自检，加入兼容 Python 3.7 的 PDF 离线依赖准备逻辑。
3. 将 SQLite/PDF 状态接入 Diagnostics，并改善错误提示。
4. 为 ChatView 增加直达最新消息按钮、可见性策略和测试。
5. 加强 ContextManager/AgentLoop 自动压缩触发、余量、状态反馈和防抖。
6. 更新 README，执行定向测试、完整测试与打包验证。
