# Win7 SP1 x64 环境 POC 取证清单（切片 26c，v2.4 §14.2）

目标环境：Windows 7 专业版 SP1 x64、8GB 内存、内网无外网。
执行人：用户在 Win7 实机操作；每项记录「证据」（命令输出/截图路径/日期）。
判定：任何一项失败 → 先修复或回退，不得带入主线（§14.1 工作守则第 6 条）。

## 使用方法

1. 将 `minion/` 发布目录与 `scripts/win7-poc.sh` 拷到 Win7 实机（Git Bash 可用）。
2. Git Bash 内运行：`bash win7-poc.sh <minion目录>`（自动跑可在脚本内验证的项并汇总）。
3. 手工项（标注 ✋）逐项操作并截图/记录到本文件「证据」栏。
4. 全部完成后把本文件提交回开发机仓库，作为切片 38（IDE 桥接）与 §14.4 总验收的前置证据。

## 清单

| # | 项目 | 验证方式 | 通过标准 | 证据（实机填写） |
|---|------|----------|----------|------------------|
| 1 | Zulu 8 x64 + JavaFX | `java -version`；启动 minion GUI | 显示 1.8.0_222（64-bit）；窗口正常渲染 | |
| 2 | JNA / DPAPI | GUI 内 `/doctor` → JNA/DPAPI 行 | 「Crypt32 可用（DPAPI 正常）」 | |
| 3 | rg.exe 架构/版本/哈希 | `ripgrep/rg.exe --version`；`sha256sum` | x64；版本 ≥ 13；记录 SHA-256 | |
| 4 | Git Bash | `bash --version`；Bash 工具跑 `echo ok` | ≥ 4.x；工具输出 ok | |
| 5 | Git x64 | `git --version` | x64 或 32 位可用（记录即可） | |
| 6 | SystemTray + 声音 | ✋ GUI 通知（notify.channel=tray）出现气泡；声音播放 | 气泡可见、声音可闻 | |
| 7 | LSP server | ✋ 配置 `lsp.servers.python=<内网 server 命令>` 后 LSP 工具 hover | 至少一个 server 握手成功并返回结果 | |
| 8 | jsoup/html2md | 脚本项 8（内网 WebFetch 抓取本机测试页） | 正文提取为 Markdown | |
| 9 | WebP 解码 | 脚本项 9（读 WebP 样例图） | ✋ 用真实 WebP 样例验证；失败则登记「放弃 WebP，降级不支持」（裁决 2026-11-22） | |
| 10 | 候选 IDE + 插件 API | ✋ 列出内网可用 IDE（VS Code 离线版/Eclipse/IDEA 离线）及其插件 API 摘要 | 满足 §12.3 证据要求或取得正式例外 | |
| 11 | 8GB 并发压测 | ✋ 开 2 个会话 + 1 teammate + MCP server | 无 OOM、无句柄泄漏（任务管理器观察 10 分钟） | |
| 12 | 断网防逃逸 | ✋ 拔网线后 WebFetch 白名单外域名 | 拒绝且不挂起 | |

## 脚本覆盖项（win7-poc.sh 自动输出）

- java -version / os.arch / sun.arch.data.model
- javap 探测 JavaFX（jfxrt.jar 存在性）
- DPAPI 经 Crypt32（调用 minion 内置探针不可行时，用 PowerShell ConvertTo-SecureString 替代初判）
- rg.exe --version + PE Machine 类型 + SHA-256
- bash/git 版本
- 内网 HTTP 连通（model.url 探测）

## 结论栏（全部完成后填写）

- 日期：
- 执行人：
- 总体判定：☐ 全部通过 ☐ 有失败项（列明处置）
- 失败项处置：
